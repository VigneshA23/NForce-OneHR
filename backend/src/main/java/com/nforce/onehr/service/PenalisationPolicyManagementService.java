package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.penalization.ClonePenalisationPolicyRequest;
import com.nforce.onehr.dto.penalization.CreatePenalisationPolicyRequest;
import com.nforce.onehr.dto.penalization.PenalisationPolicySummaryDto;
import com.nforce.onehr.dto.penalization.RenamePenalisationPolicyRequest;
import com.nforce.onehr.entity.PenalisationPolicy;
import com.nforce.onehr.entity.PenalizationPolicyLateHoursTier;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.entity.PenalizationPolicyWorkHoursTier;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.PenalisationPolicyRepository;
import com.nforce.onehr.repository.PenalizationPolicyAllocationRepository;
import com.nforce.onehr.repository.PenalizationPolicyLateHoursTierRepository;
import com.nforce.onehr.repository.PenalizationPolicyVersionRepository;
import com.nforce.onehr.repository.PenalizationPolicyWorkHoursTierRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Organization Masters → Penalization Policy → Policy List (Section 5): CRUD for the
 * {@link PenalisationPolicy} label/assignment entity itself (name, description, status),
 * distinct from {@link PenalizationPolicyService} which owns each policy's rule-configuration
 * version chain. Named "Management" rather than reusing either existing name to avoid the
 * British/American-spelling ambiguity already flagged between {@code PenalisationPolicy} (V95,
 * assignment label) and {@code PenalizationPolicy*} (rule config).
 */
@Service
@RequiredArgsConstructor
public class PenalisationPolicyManagementService {

    private final PenalisationPolicyRepository penalisationPolicyRepository;
    private final PenalizationPolicyVersionRepository versionRepository;
    private final PenalizationPolicyWorkHoursTierRepository tierRepository;
    private final PenalizationPolicyLateHoursTierRepository lateHoursTierRepository;
    private final PenalizationPolicyAllocationRepository allocationRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final AttendanceProperties attendanceProperties;
    private final PenalizationPolicyResolutionService resolutionService;

    private LocalDate today() {
        return LocalDateTime.now(ZoneId.of(attendanceProperties.getZone())).toLocalDate();
    }

    /**
     * Every policy's "Employee Count" here comes from
     * {@link PenalizationPolicyResolutionService#resolveCurrentEmployeeCountsByPolicy} — the same
     * authoritative, allocation-aware resolution the Penalization Policy Allocation screen and the
     * attendance engine use — computed ONCE for the whole list (two bulk queries total) rather
     * than once per policy, so this scales with employee count, not policy count × employee count.
     * Likewise, every policy's "current version" (currentVersion/effectiveFrom columns) is fetched
     * in ONE bulk query up front — {@link #toSummary(PenalisationPolicy, long)} does its own
     * single-policy lookup for the single-object callers (create/rename/toggleActive/delete),
     * but calling that per policy here was a genuine N+1 (one findByPolicyIdAndEffectiveToIsNull
     * round trip per row) that dominated this list's load time.
     */
    @Transactional(readOnly = true)
    public List<PenalisationPolicySummaryDto> list() {
        List<PenalisationPolicy> policies = penalisationPolicyRepository.findAll();
        List<UUID> policyIds = policies.stream().map(PenalisationPolicy::getId).toList();
        Map<UUID, PenalizationPolicyVersion> currentVersionByPolicyId = policyIds.isEmpty() ? Map.of()
                : versionRepository.findByPolicyIdInAndEffectiveToIsNull(policyIds).stream()
                        .collect(Collectors.toMap(PenalizationPolicyVersion::getPolicyId, v -> v));
        // The org default policy id (oldest by createdAt — same rule as
        // PenalizationPolicyService#resolveDefaultPolicyId) is derived from the `policies` list
        // already in hand, instead of letting resolveCurrentEmployeeCountsByPolicy issue its own
        // separate lookup query for it.
        UUID defaultPolicyId = policies.stream()
                .min(Comparator.comparing(PenalisationPolicy::getCreatedAt))
                .map(PenalisationPolicy::getId).orElse(null);
        Map<UUID, Long> counts = resolutionService.resolveCurrentEmployeeCountsByPolicy(today(), defaultPolicyId);
        return policies.stream()
                .map(p -> toSummary(p, counts.getOrDefault(p.getId(), 0L), currentVersionByPolicyId.get(p.getId())))
                .sorted(Comparator.comparing(PenalisationPolicySummaryDto::getCreatedAt))
                .toList();
    }

    @Transactional
    public PenalisationPolicySummaryDto create(CreatePenalisationPolicyRequest request, String actorEmail) {
        User actor = resolveActor(actorEmail);
        if (penalisationPolicyRepository.findByName(request.getName()).isPresent()) {
            throw new IllegalStateException("A policy named '" + request.getName() + "' already exists");
        }
        PenalisationPolicy policy = penalisationPolicyRepository.save(PenalisationPolicy.builder()
                .name(request.getName()).description(request.getDescription())
                .createdBy(actor.getId()).status("ACTIVE").build());
        auditService.log(actor.getId(), "PENALISATION_POLICY_CREATED", policy.getId());
        return toSummary(policy, 0L);
    }

    @Transactional
    public PenalisationPolicySummaryDto rename(UUID id, RenamePenalisationPolicyRequest request, String actorEmail) {
        User actor = resolveActor(actorEmail);
        PenalisationPolicy policy = findPolicy(id);
        penalisationPolicyRepository.findByName(request.getName())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new IllegalStateException("A policy named '" + request.getName() + "' already exists");
                });
        String before = auditSnapshot.toJson(Map.of("name", policy.getName()));
        policy.setName(request.getName());
        if (request.getDescription() != null) {
            policy.setDescription(request.getDescription());
        }
        penalisationPolicyRepository.save(policy);
        auditService.log(actor.getId(), "PENALISATION_POLICY_RENAMED", policy.getId(),
                before, auditSnapshot.toJson(Map.of("name", policy.getName())));
        return toSummary(policy, resolutionService.resolveCurrentEmployeeCount(policy.getId(), today()));
    }

    /**
     * Copies the source policy's currently-effective rule configuration (all four sections, all
     * tier tables) into a brand-new, independently-versioned policy — immediately effective (not
     * "1st of next month": there is no prior version on the new policy to supersede, so
     * {@link PenalizationPolicyService}'s supersede-scheduling rule doesn't apply here).
     */
    @Transactional
    public PenalisationPolicySummaryDto clone(UUID sourceId, ClonePenalisationPolicyRequest request, String actorEmail) {
        User actor = resolveActor(actorEmail);
        findPolicy(sourceId); // 404s early if the source doesn't exist
        if (penalisationPolicyRepository.findByName(request.getName()).isPresent()) {
            throw new IllegalStateException("A policy named '" + request.getName() + "' already exists");
        }

        PenalisationPolicy clone = penalisationPolicyRepository.save(PenalisationPolicy.builder()
                .name(request.getName()).description(request.getDescription())
                .createdBy(actor.getId()).status("ACTIVE").build());

        versionRepository.findByPolicyIdAndEffectiveToIsNull(sourceId).ifPresent(source -> {
            LocalDateTime now = LocalDateTime.now(ZoneId.of(attendanceProperties.getZone()));
            PenalizationPolicyVersion copy = copyVersionFields(source, clone.getId(), now, actor.getId());
            copy = versionRepository.save(copy);
            for (PenalizationPolicyWorkHoursTier t : tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(source.getId())) {
                tierRepository.save(PenalizationPolicyWorkHoursTier.builder()
                        .policyVersionId(copy.getId()).thresholdPercent(t.getThresholdPercent())
                        .deductionDays(t.getDeductionDays()).sortOrder(t.getSortOrder()).build());
            }
            for (PenalizationPolicyLateHoursTier t : lateHoursTierRepository.findByPolicyVersionIdOrderBySortOrderAsc(source.getId())) {
                lateHoursTierRepository.save(PenalizationPolicyLateHoursTier.builder()
                        .policyVersionId(copy.getId()).thresholdHours(t.getThresholdHours())
                        .deductionDays(t.getDeductionDays()).sortOrder(t.getSortOrder()).build());
            }
        });

        auditService.log(actor.getId(), "PENALISATION_POLICY_CLONED", clone.getId(),
                auditSnapshot.toJson(Map.of("clonedFromPolicyId", sourceId.toString())), null);
        return toSummary(clone, 0L);
    }

    /**
     * Deactivating a policy removes it from the active allocation/assignment dropdowns (see
     * {@code PenalisationPolicySummaryDto#getStatus} and the frontend's active-policy filter) —
     * it does not touch any existing allocation, legacy FK assignment, or historical attendance
     * result. Reactivating simply flips it back. Employees currently resolving to a deactivated
     * policy keep resolving to it (deactivation is "don't let anyone else pick this" — retiring an
     * in-use policy without reassigning first is an explicit admin decision, not something this
     * toggle does on their behalf).
     */
    @Transactional
    public PenalisationPolicySummaryDto toggleActive(UUID id, String actorEmail) {
        User actor = resolveActor(actorEmail);
        PenalisationPolicy policy = findPolicy(id);
        String before = auditSnapshot.toJson(Map.of("status", policy.getStatus()));
        policy.setStatus("ACTIVE".equals(policy.getStatus()) ? "INACTIVE" : "ACTIVE");
        penalisationPolicyRepository.save(policy);
        auditService.log(actor.getId(), "PENALISATION_POLICY_STATUS_CHANGED", policy.getId(),
                before, auditSnapshot.toJson(Map.of("status", policy.getStatus())));
        return toSummary(policy, resolutionService.resolveCurrentEmployeeCount(id, today()));
    }

    /**
     * Blocked while any employee is still assigned to this policy, and blocked for the org's last
     * remaining policy — every employee must always have a policy to fall back to.
     */
    @Transactional
    public void delete(UUID id, String actorEmail) {
        User actor = resolveActor(actorEmail);
        PenalisationPolicy policy = findPolicy(id);
        long employeeCount = resolutionService.resolveCurrentEmployeeCount(id, today());
        if (employeeCount > 0) {
            throw new IllegalStateException(employeeCount + " employee(s) are currently assigned to this policy. Reassign them first.");
        }
        long allocationCount = allocationRepository.countByPenalisationPolicyId(id);
        if (allocationCount > 0) {
            throw new IllegalStateException(allocationCount
                    + " Penalization Policy Allocation record(s) reference this policy. Remove or reassign them first.");
        }
        if (penalisationPolicyRepository.count() <= 1) {
            throw new IllegalStateException("Cannot delete the organization's only remaining Penalization Policy.");
        }
        for (PenalizationPolicyVersion version : versionRepository.findByPolicyIdOrderByVersionDesc(id)) {
            tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId()).forEach(tierRepository::delete);
            lateHoursTierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId()).forEach(lateHoursTierRepository::delete);
            versionRepository.delete(version);
        }
        penalisationPolicyRepository.delete(policy);
        auditService.log(actor.getId(), "PENALISATION_POLICY_DELETED", id,
                auditSnapshot.toJson(Map.of("name", policy.getName())), null);
    }

    private PenalizationPolicyVersion copyVersionFields(PenalizationPolicyVersion source, UUID newPolicyId,
                                                         LocalDateTime effectiveFrom, UUID actorId) {
        return PenalizationPolicyVersion.builder()
                .policyId(newPolicyId).version(1).effectiveFrom(effectiveFrom)
                .noAttendanceEnabled(source.isNoAttendanceEnabled()).naDeductionDays(source.getNaDeductionDays())
                .naNoShowEnabled(source.isNaNoShowEnabled()).naNoShowThresholdHours(source.getNaNoShowThresholdHours())
                .naAdjoiningHolidayEnabled(source.isNaAdjoiningHolidayEnabled())
                .naAdjoiningHolidayCondition(source.getNaAdjoiningHolidayCondition())
                .naAdjoiningHolidayCalendarDayThreshold(source.getNaAdjoiningHolidayCalendarDayThreshold())
                .naAdjoiningHolidayIgnoreHalfDayLeave(source.isNaAdjoiningHolidayIgnoreHalfDayLeave())
                .naAdjoiningWeekoffEnabled(source.isNaAdjoiningWeekoffEnabled())
                .naAdjoiningWeekoffCondition(source.getNaAdjoiningWeekoffCondition())
                .naAdjoiningWeekoffCalendarDayThreshold(source.getNaAdjoiningWeekoffCalendarDayThreshold())
                .naAdjoiningWeekoffIgnoreHalfDayLeave(source.isNaAdjoiningWeekoffIgnoreHalfDayLeave())
                .lateArrivalEnabled(source.isLateArrivalEnabled()).laBasis(source.getLaBasis())
                .laGracePeriodMinutes(source.getLaGracePeriodMinutes()).laExemptCount(source.getLaExemptCount())
                .laExemptPeriod(source.getLaExemptPeriod()).laDeductionDays(source.getLaDeductionDays())
                .laDeductionPerShifts(source.getLaDeductionPerShifts())
                .laIgnoreWhenEffectiveHoursMetEnabled(source.isLaIgnoreWhenEffectiveHoursMetEnabled())
                .laAllowedHours(source.getLaAllowedHours()).laCombinedRuleBehavior(source.getLaCombinedRuleBehavior())
                .laPenaliseWhenCausedByMissingLogEnabled(source.isLaPenaliseWhenCausedByMissingLogEnabled())
                .workHoursShortageEnabled(source.isWorkHoursShortageEnabled())
                .whsDeductionBasis(source.getWhsDeductionBasis()).whsDeductionPeriod(source.getWhsDeductionPeriod())
                .whsApplyPenaltyForShortageEnabled(source.isWhsApplyPenaltyForShortageEnabled())
                .whsApplyPenaltyForLateArrivalEnabled(source.isWhsApplyPenaltyForLateArrivalEnabled())
                .whsExcludeHoursOutsideShiftEnabled(source.isWhsExcludeHoursOutsideShiftEnabled())
                .whsPenalizeShortageCausedByMissingLogsEnabled(source.isWhsPenalizeShortageCausedByMissingLogsEnabled())
                .missingLogsEnabled(source.isMissingLogsEnabled()).mlExemptDays(source.getMlExemptDays())
                .mlExemptPeriod(source.getMlExemptPeriod()).mlDeductionMode(source.getMlDeductionMode())
                .mlDeductionDays(source.getMlDeductionDays()).mlDeductionPerShifts(source.getMlDeductionPerShifts())
                .mlIgnoreRuleEnabled(source.isMlIgnoreRuleEnabled())
                .mlIgnoreRuleThresholdPercent(source.getMlIgnoreRuleThresholdPercent())
                .deductionMethod(source.getDeductionMethod()).leavePriorityOrder(source.getLeavePriorityOrder())
                .bufferPeriodDays(source.getBufferPeriodDays())
                .noticePeriodForcesLopEnabled(source.isNoticePeriodForcesLopEnabled())
                .createdBy(actorId)
                .build();
    }

    // Single-policy lookup for the single-object callers (create/rename/toggleActive/delete) —
    // one query is correct here since each only handles one policy per call.
    private PenalisationPolicySummaryDto toSummary(PenalisationPolicy policy, long employeeCount) {
        return toSummary(policy, employeeCount, versionRepository.findByPolicyIdAndEffectiveToIsNull(policy.getId()).orElse(null));
    }

    // Bulk-list variant: the current version is passed in, already resolved in one batched query
    // by the caller (see #list) — this overload itself never queries.
    private PenalisationPolicySummaryDto toSummary(PenalisationPolicy policy, long employeeCount, PenalizationPolicyVersion current) {
        return PenalisationPolicySummaryDto.builder()
                .id(policy.getId()).name(policy.getName()).description(policy.getDescription())
                .status(policy.getStatus()).employeeCount(employeeCount)
                .currentVersion(current != null ? current.getVersion() : null)
                .effectiveFrom(current != null ? current.getEffectiveFrom() : null)
                .createdBy(policy.getCreatedBy()).createdAt(policy.getCreatedAt())
                .build();
    }

    private PenalisationPolicy findPolicy(UUID id) {
        return penalisationPolicyRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Penalisation Policy not found"));
    }

    private User resolveActor(String actorEmail) {
        return userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }
}
