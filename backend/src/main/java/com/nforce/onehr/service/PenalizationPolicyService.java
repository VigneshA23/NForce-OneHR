package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.penalization.*;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.PenalisationPolicy;
import com.nforce.onehr.entity.PenalizationPolicyLateHoursTier;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.entity.PenalizationPolicyWorkHoursTier;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PenalisationPolicyRepository;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Organization Masters → Penalization Policy. Owns the only write path for
 * {@link PenalizationPolicyVersion} — every save creates a new immutable version and closes the
 * previous one's {@code effectiveTo}; nothing here ever mutates a version's configuration values
 * after creation (see {@link PenalizationPolicyVersion} class javadoc).
 *
 * <p>Effective-date behavior implements exactly what the approved screenshots demonstrate: a
 * save becomes effective from the 1st of the calendar month after the save date ("saved during
 * July → effective from August 1"). No weekly-cycle variant is implemented — the screenshots
 * never show one operating, only a monthly one, repeated identically three times.
 *
 * <p>Phase 2: every method accepts an optional {@code policyId} — the specific
 * {@link PenalisationPolicy} (Section 5's Policy List) this configuration belongs to. Passing
 * {@code null} resolves to {@link #resolveDefaultPolicyId()} (the org's original single policy),
 * preserving every Phase 1 caller's behavior unchanged.
 */
@Service
@RequiredArgsConstructor
public class PenalizationPolicyService {

    private final PenalizationPolicyVersionRepository versionRepository;
    private final PenalizationPolicyWorkHoursTierRepository tierRepository;
    private final PenalizationPolicyLateHoursTierRepository lateHoursTierRepository;
    private final PenalisationPolicyRepository penalisationPolicyRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer snapshotSerializer;
    private final AttendanceProperties attendanceProperties;
    private final EmployeeRepository employeeRepository;
    private final NotificationService notificationService;

    private static final DateTimeFormatter NOTIFICATION_DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");

    @Transactional(readOnly = true)
    public Optional<PenalizationPolicyResponse> getCurrent(UUID policyId) {
        return versionRepository.findByPolicyIdAndEffectiveToIsNull(resolvePolicyId(policyId)).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<PenalizationPolicyVersionSummary> getVersionHistory(UUID policyId) {
        return versionRepository.findByPolicyIdOrderByVersionDesc(resolvePolicyId(policyId)).stream()
                .map(v -> PenalizationPolicyVersionSummary.builder()
                        .id(v.getId()).version(v.getVersion())
                        .effectiveFrom(v.getEffectiveFrom()).effectiveTo(v.getEffectiveTo())
                        .createdAt(v.getCreatedAt())
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public PenalizationPolicyResponse getVersion(UUID versionId) {
        PenalizationPolicyVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new NoSuchElementException("Policy version not found"));
        return toResponse(version);
    }

    @Transactional
    public PenalizationPolicyResponse save(UUID policyId, PenalizationPolicyRequest request, String actorEmail) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
        validateTiers(request);
        validateLateHoursTiers(request);

        UUID resolvedPolicyId = resolvePolicyId(policyId);
        Optional<PenalizationPolicyVersion> current = versionRepository.findByPolicyIdAndEffectiveToIsNull(resolvedPolicyId);
        // Captured now, before anything below can mutate the managed `current` entity in place
        // (see the editingSameDateVersion merge path further down).
        String before = current.map(this::snapshotFields).map(snapshotSerializer::toJson).orElse(null);

        LocalDateTime now = LocalDateTime.now(ZoneId.of(attendanceProperties.getZone()));
        validateBasicInfo(request, current, now.toLocalDate());
        // An admin-chosen future effective date (Section 15) wins; omitting it preserves the
        // original behavior exactly — effective the 1st of the calendar month after the save date.
        LocalDate requestedEffectiveFrom = request.getBasicInfo().getRequestedEffectiveFrom();
        LocalDateTime effectiveFrom = requestedEffectiveFrom != null
                ? requestedEffectiveFrom.atStartOfDay()
                : now.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay();

        // Section 20: consistent with PenalizationPolicyAllocationService#update's own "unchanged"
        // short-circuit — an admin resubmitting byte-identical rule content at the same effective
        // date must not create a new version, write an audit row, or notify anyone.
        if (current.isPresent() && isUnchanged(request, current.get(), effectiveFrom)) {
            return toResponse(current.get());
        }

        // Section 4: an explicit requestedEffectiveFrom can never equal current's own date
        // (validateBasicInfo requires strictly after), but the DEFAULT "1st of next month" date
        // can coincidentally land on it (e.g. re-saving later the same month, still ahead of a
        // version that was itself scheduled for next month). Closing `current` in that case would
        // set its effectiveTo to one nanosecond BEFORE its own effectiveFrom — a negative-length
        // range. Since the content here differs from isUnchanged's check above, this is a genuine
        // edit of that same still-pending version's rule content, done in place (same row/id) —
        // exactly like #save's other create-new/close-old path, just without a new point in the
        // version chain to add. That version can never have been referenced by any
        // AttendancePenalty yet (nothing evaluates a not-yet-effective policy).
        boolean editingSameDateVersion = current.isPresent() && effectiveFrom.equals(current.get().getEffectiveFrom());

        int versionNumber = editingSameDateVersion
                ? current.get().getVersion()
                : current.map(v -> v.getVersion() + 1).orElse(1);

        if (!editingSameDateVersion) {
            // Close the version being superseded — a bookkeeping update to its temporal boundary
            // only, never to its configuration values (see EmployeeManagerHistory for the same
            // close-on-supersede convention already used elsewhere in this codebase).
            current.ifPresent(previous -> {
                previous.setEffectiveTo(effectiveFrom.minusNanos(1));
                previous.setUpdatedBy(actor.getId());
                versionRepository.save(previous);
            });
        }

        PenalizationPolicyVersion.PenalizationPolicyVersionBuilder versionBuilder = PenalizationPolicyVersion.builder()
                .policyId(resolvedPolicyId)
                .version(versionNumber)
                .effectiveFrom(effectiveFrom)
                .noAttendanceEnabled(request.getNoAttendance().isEnabled())
                .naDeductionDays(request.getNoAttendance().getDeductionDays())
                .naNoShowEnabled(request.getNoAttendance().isNoShowEnabled())
                .naNoShowThresholdHours(request.getNoAttendance().getNoShowThresholdHours())
                .naAdjoiningHolidayEnabled(request.getNoAttendance().isAdjoiningHolidayEnabled())
                .naAdjoiningHolidayCondition(request.getNoAttendance().getAdjoiningHolidayCondition())
                .naAdjoiningHolidayCalendarDayThreshold(request.getNoAttendance().getAdjoiningHolidayCalendarDayThreshold())
                .naAdjoiningHolidayIgnoreHalfDayLeave(request.getNoAttendance().isAdjoiningHolidayIgnoreHalfDayLeave())
                .naAdjoiningWeekoffEnabled(request.getNoAttendance().isAdjoiningWeekoffEnabled())
                .naAdjoiningWeekoffCondition(request.getNoAttendance().getAdjoiningWeekoffCondition())
                .naAdjoiningWeekoffCalendarDayThreshold(request.getNoAttendance().getAdjoiningWeekoffCalendarDayThreshold())
                .naAdjoiningWeekoffIgnoreHalfDayLeave(request.getNoAttendance().isAdjoiningWeekoffIgnoreHalfDayLeave())
                .lateArrivalEnabled(request.getLateArrival().isEnabled())
                .laBasis(request.getLateArrival().getBasis())
                .laGracePeriodMinutes(request.getLateArrival().getGracePeriodMinutes())
                .laExemptCount(request.getLateArrival().getExemptCount())
                .laExemptPeriod(request.getLateArrival().getExemptPeriod())
                .laDeductionDays(request.getLateArrival().getDeductionDays())
                .laDeductionPerShifts(request.getLateArrival().getDeductionPerShifts())
                .laIgnoreWhenEffectiveHoursMetEnabled(request.getLateArrival().isIgnoreWhenEffectiveHoursMetEnabled())
                .laAllowedHours(request.getLateArrival().getAllowedHours())
                .laCombinedRuleBehavior(request.getLateArrival().getCombinedRuleBehavior())
                .laPenaliseWhenCausedByMissingLogEnabled(request.getLateArrival().isPenaliseWhenCausedByMissingLogEnabled())
                .workHoursShortageEnabled(request.getWorkHoursShortage().isEnabled())
                .whsDeductionBasis(request.getWorkHoursShortage().getDeductionBasis())
                .whsDeductionPeriod(request.getWorkHoursShortage().getDeductionPeriod())
                .whsApplyPenaltyForShortageEnabled(request.getWorkHoursShortage().isApplyPenaltyForShortageEnabled())
                .whsApplyPenaltyForLateArrivalEnabled(request.getWorkHoursShortage().isApplyPenaltyForLateArrivalEnabled())
                .whsExcludeHoursOutsideShiftEnabled(request.getWorkHoursShortage().isExcludeHoursOutsideShiftEnabled())
                .whsPenalizeShortageCausedByMissingLogsEnabled(request.getWorkHoursShortage().isPenalizeShortageCausedByMissingLogsEnabled())
                .missingLogsEnabled(request.getMissingLogs().isEnabled())
                .mlExemptDays(request.getMissingLogs().getExemptDays())
                .mlExemptPeriod(request.getMissingLogs().getExemptPeriod())
                .mlDeductionMode(request.getMissingLogs().getDeductionMode())
                .mlDeductionDays(request.getMissingLogs().getDeductionDays())
                .mlDeductionPerShifts(request.getMissingLogs().getDeductionPerShifts())
                .mlIgnoreRuleEnabled(request.getMissingLogs().isIgnoreRuleEnabled())
                .mlIgnoreRuleThresholdPercent(request.getMissingLogs().getIgnoreRuleThresholdPercent())
                .deductionMethod(request.getBasicInfo().getDeductionMethod())
                .leavePriorityOrder(request.getBasicInfo().getLeavePriorityOrder() == null
                        || request.getBasicInfo().getLeavePriorityOrder().isEmpty() ? null
                        : String.join(",", request.getBasicInfo().getLeavePriorityOrder()))
                .bufferPeriodDays(request.getBasicInfo().getBufferPeriodDays())
                .noticePeriodForcesLopEnabled(request.getBasicInfo().isNoticePeriodForcesLopEnabled())
                .createdBy(actor.getId())
                .updatedBy(actor.getId());
        if (editingSameDateVersion) {
            // Same row, same origin — save() below merges onto it in place instead of inserting a
            // new one; preserve who/when it was originally scheduled rather than overwriting it
            // with this edit's actor (the audit log entry already records who made this edit).
            versionBuilder.id(current.get().getId())
                    .createdBy(current.get().getCreatedBy())
                    .createdAt(current.get().getCreatedAt());
        }
        PenalizationPolicyVersion version = versionBuilder.build();
        version = versionRepository.save(version);

        if (editingSameDateVersion) {
            tierRepository.deleteByPolicyVersionId(version.getId());
            lateHoursTierRepository.deleteByPolicyVersionId(version.getId());
        }

        List<WorkHoursTierDto> tierDtos = request.getWorkHoursShortage().getTiers();
        for (int i = 0; i < tierDtos.size(); i++) {
            WorkHoursTierDto t = tierDtos.get(i);
            tierRepository.save(PenalizationPolicyWorkHoursTier.builder()
                    .policyVersionId(version.getId())
                    .thresholdPercent(t.getThresholdPercent())
                    .deductionDays(t.getDeductionDays())
                    .sortOrder(i)
                    .build());
        }

        List<LateHoursTierDto> lateHoursTierDtos = request.getLateArrival().getLateHoursTiers();
        for (int i = 0; i < lateHoursTierDtos.size(); i++) {
            LateHoursTierDto t = lateHoursTierDtos.get(i);
            lateHoursTierRepository.save(PenalizationPolicyLateHoursTier.builder()
                    .policyVersionId(version.getId())
                    .thresholdHours(t.getThresholdHours())
                    .deductionDays(t.getDeductionDays())
                    .sortOrder(i)
                    .build());
        }

        String action = current.isEmpty() ? "PENALIZATION_POLICY_CREATED" : "PENALIZATION_POLICY_UPDATED";
        String after = snapshotSerializer.toJson(snapshotFields(version));
        auditService.log(actor.getId(), action, version.getId(), before, after);

        // Section 45: only a genuine mid-cycle CHANGE to an already-effective policy needs to
        // notify anyone — a brand-new policy (first save) has no one currently relying on prior
        // behavior yet.
        if (current.isPresent()) {
            notifyAffectedEmployeesOfPolicyChange(resolvedPolicyId, version);
        }

        return toResponse(version);
    }

    /**
     * Reuses the existing {@link NotificationService} — the same one-row-per-recipient broadcast
     * pattern {@code PolicyService#publishPolicy} already uses for "New Policy" alerts — rather
     * than introducing a separate notification mechanism. Every employee whose Penalization
     * Policy is about to change (explicitly assigned to {@code policyId}, or implicitly via the
     * org-default fallback when {@code policyId} IS that default — see
     * {@link #resolveAssignedOrDefaultPolicyId} in {@link PenalizationPolicyResolutionService})
     * gets one row telling them what changed and from when. Historical attendance already
     * evaluated against the version being superseded is unaffected — this is purely an
     * informational heads-up, not a recalculation trigger.
     */
    private void notifyAffectedEmployeesOfPolicyChange(UUID policyId, PenalizationPolicyVersion newVersion) {
        List<Employee> affected = new ArrayList<>(employeeRepository.findByPenalisationPolicy_Id(policyId));
        try {
            if (policyId.equals(resolveDefaultPolicyId())) {
                affected.addAll(employeeRepository.findByPenalisationPolicyIsNull());
            }
        } catch (IllegalStateException e) {
            // No PenalisationPolicy row exists at all (shouldn't happen given the V95 seed) — the
            // explicitly-assigned recipients resolved above still get notified regardless.
        }
        if (affected.isEmpty()) {
            return;
        }
        String effectiveDate = newVersion.getEffectiveFrom().toLocalDate().format(NOTIFICATION_DATE_FMT);
        for (Employee employee : affected) {
            notificationService.send(employee.getUserId(), "PENALIZATION_POLICY_CHANGED",
                    "Attendance Penalization Policy Updated",
                    "Your attendance penalization policy has changed and takes effect from " + effectiveDate
                            + ". Attendance before that date continues to be evaluated under the previous configuration.",
                    "/attendance");
        }
    }

    private Map<String, Object> snapshotFields(PenalizationPolicyVersion v) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("version", v.getVersion());
        fields.put("effectiveFrom", v.getEffectiveFrom());
        fields.put("noAttendanceEnabled", v.isNoAttendanceEnabled());
        fields.put("naDeductionDays", v.getNaDeductionDays());
        fields.put("naAdjoiningHolidayEnabled", v.isNaAdjoiningHolidayEnabled());
        fields.put("naAdjoiningWeekoffEnabled", v.isNaAdjoiningWeekoffEnabled());
        fields.put("lateArrivalEnabled", v.isLateArrivalEnabled());
        fields.put("laBasis", v.getLaBasis());
        fields.put("laGracePeriodMinutes", v.getLaGracePeriodMinutes());
        fields.put("laExemptCount", v.getLaExemptCount());
        fields.put("laDeductionDays", v.getLaDeductionDays());
        fields.put("laAllowedHours", v.getLaAllowedHours());
        fields.put("workHoursShortageEnabled", v.isWorkHoursShortageEnabled());
        fields.put("missingLogsEnabled", v.isMissingLogsEnabled());
        fields.put("mlExemptDays", v.getMlExemptDays());
        fields.put("deductionMethod", v.getDeductionMethod());
        fields.put("leavePriorityOrder", v.getLeavePriorityOrder());
        fields.put("bufferPeriodDays", v.getBufferPeriodDays());
        fields.put("noticePeriodForcesLopEnabled", v.isNoticePeriodForcesLopEnabled());
        return fields;
    }

    private PenalizationPolicyResponse toResponse(PenalizationPolicyVersion v) {
        List<WorkHoursTierDto> tiers = tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(v.getId()).stream()
                .map(t -> {
                    WorkHoursTierDto dto = new WorkHoursTierDto();
                    dto.setThresholdPercent(t.getThresholdPercent());
                    dto.setDeductionDays(t.getDeductionDays());
                    return dto;
                })
                .collect(Collectors.toList());

        List<LateHoursTierDto> lateHoursTiers = lateHoursTierRepository.findByPolicyVersionIdOrderBySortOrderAsc(v.getId()).stream()
                .map(t -> {
                    LateHoursTierDto dto = new LateHoursTierDto();
                    dto.setThresholdHours(t.getThresholdHours());
                    dto.setDeductionDays(t.getDeductionDays());
                    return dto;
                })
                .collect(Collectors.toList());

        NoAttendanceConfigDto noAttendance = new NoAttendanceConfigDto();
        noAttendance.setEnabled(v.isNoAttendanceEnabled());
        noAttendance.setDeductionDays(v.getNaDeductionDays());
        noAttendance.setNoShowEnabled(v.isNaNoShowEnabled());
        noAttendance.setNoShowThresholdHours(v.getNaNoShowThresholdHours());
        noAttendance.setAdjoiningHolidayEnabled(v.isNaAdjoiningHolidayEnabled());
        noAttendance.setAdjoiningHolidayCondition(v.getNaAdjoiningHolidayCondition());
        noAttendance.setAdjoiningHolidayCalendarDayThreshold(v.getNaAdjoiningHolidayCalendarDayThreshold());
        noAttendance.setAdjoiningHolidayIgnoreHalfDayLeave(v.isNaAdjoiningHolidayIgnoreHalfDayLeave());
        noAttendance.setAdjoiningWeekoffEnabled(v.isNaAdjoiningWeekoffEnabled());
        noAttendance.setAdjoiningWeekoffCondition(v.getNaAdjoiningWeekoffCondition());
        noAttendance.setAdjoiningWeekoffCalendarDayThreshold(v.getNaAdjoiningWeekoffCalendarDayThreshold());
        noAttendance.setAdjoiningWeekoffIgnoreHalfDayLeave(v.isNaAdjoiningWeekoffIgnoreHalfDayLeave());

        LateArrivalConfigDto lateArrival = new LateArrivalConfigDto();
        lateArrival.setEnabled(v.isLateArrivalEnabled());
        lateArrival.setBasis(v.getLaBasis());
        lateArrival.setGracePeriodMinutes(v.getLaGracePeriodMinutes());
        lateArrival.setExemptCount(v.getLaExemptCount());
        lateArrival.setExemptPeriod(v.getLaExemptPeriod());
        lateArrival.setDeductionDays(v.getLaDeductionDays());
        lateArrival.setDeductionPerShifts(v.getLaDeductionPerShifts());
        lateArrival.setIgnoreWhenEffectiveHoursMetEnabled(v.isLaIgnoreWhenEffectiveHoursMetEnabled());
        lateArrival.setAllowedHours(v.getLaAllowedHours());
        lateArrival.setLateHoursTiers(lateHoursTiers);
        lateArrival.setCombinedRuleBehavior(v.getLaCombinedRuleBehavior());
        lateArrival.setPenaliseWhenCausedByMissingLogEnabled(v.isLaPenaliseWhenCausedByMissingLogEnabled());

        WorkHoursShortageConfigDto workHours = new WorkHoursShortageConfigDto();
        workHours.setEnabled(v.isWorkHoursShortageEnabled());
        workHours.setDeductionBasis(v.getWhsDeductionBasis());
        workHours.setDeductionPeriod(v.getWhsDeductionPeriod());
        workHours.setTiers(tiers);
        workHours.setApplyPenaltyForShortageEnabled(v.isWhsApplyPenaltyForShortageEnabled());
        workHours.setApplyPenaltyForLateArrivalEnabled(v.isWhsApplyPenaltyForLateArrivalEnabled());
        workHours.setExcludeHoursOutsideShiftEnabled(v.isWhsExcludeHoursOutsideShiftEnabled());
        workHours.setPenalizeShortageCausedByMissingLogsEnabled(v.isWhsPenalizeShortageCausedByMissingLogsEnabled());

        MissingLogsConfigDto missingLogs = new MissingLogsConfigDto();
        missingLogs.setEnabled(v.isMissingLogsEnabled());
        missingLogs.setExemptDays(v.getMlExemptDays());
        missingLogs.setExemptPeriod(v.getMlExemptPeriod());
        missingLogs.setDeductionMode(v.getMlDeductionMode());
        missingLogs.setDeductionDays(v.getMlDeductionDays());
        missingLogs.setDeductionPerShifts(v.getMlDeductionPerShifts());
        missingLogs.setIgnoreRuleEnabled(v.isMlIgnoreRuleEnabled());
        missingLogs.setIgnoreRuleThresholdPercent(v.getMlIgnoreRuleThresholdPercent());

        BasicInfoConfigDto basicInfo = new BasicInfoConfigDto();
        basicInfo.setDeductionMethod(v.getDeductionMethod());
        basicInfo.setLeavePriorityOrder(v.getLeavePriorityOrder() == null || v.getLeavePriorityOrder().isBlank()
                ? List.of() : List.of(v.getLeavePriorityOrder().split(",")));
        basicInfo.setBufferPeriodDays(v.getBufferPeriodDays());
        basicInfo.setNoticePeriodForcesLopEnabled(v.isNoticePeriodForcesLopEnabled());

        return PenalizationPolicyResponse.builder()
                .id(v.getId()).policyId(v.getPolicyId()).version(v.getVersion())
                .effectiveFrom(v.getEffectiveFrom()).effectiveTo(v.getEffectiveTo())
                .basicInfo(basicInfo)
                .noAttendance(noAttendance).lateArrival(lateArrival)
                .workHoursShortage(workHours).missingLogs(missingLogs)
                .createdBy(v.getCreatedBy()).createdAt(v.getCreatedAt())
                .updatedBy(v.getUpdatedBy()).updatedAt(v.getUpdatedAt())
                .build();
    }

    private UUID resolvePolicyId(UUID policyId) {
        return policyId != null ? policyId : resolveDefaultPolicyId();
    }

    /** The original single policy every employee was assigned to before Policy List existed. */
    UUID resolveDefaultPolicyId() {
        return penalisationPolicyRepository.findFirstByOrderByCreatedAtAsc()
                .map(PenalisationPolicy::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "No Penalisation Policy exists to attach this configuration to"));
    }

    /**
     * Gap-001/Section 7: the attendance-evaluation resolution chain's own default fallback. Unlike
     * {@link #resolveDefaultPolicyId()} (policy-management bookkeeping — "the org's original
     * policy" stays meaningful for that purpose regardless of active/inactive status), an
     * INACTIVE policy must never be selected to govern a new/current/future attendance
     * evaluation. Historical {@link com.nforce.onehr.entity.AttendancePenalty} rows already carry
     * their own policy/version snapshot and are never re-resolved, so this has no effect on them.
     *
     * <p>Reads the admin-chosen {@code isOrgDefault} flag (Section 7) rather than "oldest ACTIVE
     * by createdAt" — that undocumented derivation is retired as the *fallback resolution* rule
     * (it's still what a brand-new org's very first policy gets flagged as, by V152's backfill).
     * Falls through to the still-nullable exception below if the flagged default has since been
     * deactivated without a replacement being chosen — "the default itself must be ACTIVE" is
     * enforced at write time by {@link PenalisationPolicyManagementService#setOrgDefault}, this is
     * just the defensive read-time check.
     */
    UUID resolveActiveDefaultPolicyId() {
        return penalisationPolicyRepository.findByOrgDefaultTrue()
                .filter(p -> "ACTIVE".equals(p.getStatus()))
                .map(PenalisationPolicy::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "No active Penalisation Policy is configured as the organization default"));
    }

    /** Gap-001: bulk active-policy membership check, reused across the resolution chain. */
    Set<UUID> findActivePolicyIds() {
        return penalisationPolicyRepository.findByStatus("ACTIVE").stream()
                .map(PenalisationPolicy::getId)
                .collect(Collectors.toSet());
    }

    /**
     * Section 15/25: an admin-chosen {@code requestedEffectiveFrom} (optional — see
     * {@link BasicInfoConfigDto}) must be a genuine future date, and — when a version is already
     * scheduled or in effect — strictly after that version's own effective date, so two saves can
     * never produce an inverted or overlapping version chain ("future version conflicts").
     * Omitting it entirely preserves the original next-month-1st behavior untouched.
     *
     * <p>Deliberately independent of Penalization Policy Allocation's own Effective From: a
     * Policy Version's Effective From answers "from what date do these rules apply", while an
     * Allocation's Effective From answers "from what date is this employee assigned to this
     * policy" — two different HRMS concepts that must resolve together against the attendance
     * date ({@code PenalizationPolicyResolutionService}), not be forced to match each other.
     */
    private void validateBasicInfo(PenalizationPolicyRequest request, Optional<PenalizationPolicyVersion> current, LocalDate today) {
        BasicInfoConfigDto basicInfo = request.getBasicInfo();
        if ("PAID_LEAVE".equals(basicInfo.getDeductionMethod())
                && (basicInfo.getLeavePriorityOrder() == null || basicInfo.getLeavePriorityOrder().isEmpty())) {
            throw new IllegalArgumentException(
                    "At least one leave type must be configured in priority order when deduction method is Paid Leave");
        }
        LocalDate requested = basicInfo.getRequestedEffectiveFrom();
        if (requested != null) {
            if (!requested.isAfter(today)) {
                throw new IllegalArgumentException("Effective date must be in the future");
            }
            LocalDate currentEffectiveFrom = current.map(v -> v.getEffectiveFrom().toLocalDate()).orElse(null);
            if (currentEffectiveFrom != null && !requested.isAfter(currentEffectiveFrom)) {
                throw new IllegalArgumentException(
                        "Effective date must be after the currently scheduled version's effective date (" + currentEffectiveFrom + ")");
            }
        }
    }

    /**
     * Section 20: true when {@code request} would produce a byte-identical rule configuration to
     * {@code current} at the exact same {@code effectiveFrom} — every scalar rule field plus both
     * tier lists, not just the effective date. {@link java.math.BigDecimal#equals} is deliberately
     * avoided for amount fields ({@code compareTo} is used instead via {@link #bdEquals}) since it
     * treats {@code 0.50} and {@code 0.5} as different values despite being numerically identical —
     * exactly the kind of scale mismatch a request round-tripped through JSON is likely to produce,
     * which would otherwise make this check under-detect real no-ops.
     */
    private boolean isUnchanged(PenalizationPolicyRequest request, PenalizationPolicyVersion current, LocalDateTime effectiveFrom) {
        if (!effectiveFrom.equals(current.getEffectiveFrom())) {
            return false;
        }
        BasicInfoConfigDto b = request.getBasicInfo();
        NoAttendanceConfigDto na = request.getNoAttendance();
        LateArrivalConfigDto la = request.getLateArrival();
        WorkHoursShortageConfigDto whs = request.getWorkHoursShortage();
        MissingLogsConfigDto ml = request.getMissingLogs();
        String requestedLeavePriorityOrder = b.getLeavePriorityOrder() == null || b.getLeavePriorityOrder().isEmpty()
                ? null : String.join(",", b.getLeavePriorityOrder());

        boolean scalarsMatch =
                na.isEnabled() == current.isNoAttendanceEnabled()
                && bdEquals(na.getDeductionDays(), current.getNaDeductionDays())
                && na.isNoShowEnabled() == current.isNaNoShowEnabled()
                && bdEquals(na.getNoShowThresholdHours(), current.getNaNoShowThresholdHours())
                && na.isAdjoiningHolidayEnabled() == current.isNaAdjoiningHolidayEnabled()
                && Objects.equals(na.getAdjoiningHolidayCondition(), current.getNaAdjoiningHolidayCondition())
                && Objects.equals(na.getAdjoiningHolidayCalendarDayThreshold(), current.getNaAdjoiningHolidayCalendarDayThreshold())
                && na.isAdjoiningHolidayIgnoreHalfDayLeave() == current.isNaAdjoiningHolidayIgnoreHalfDayLeave()
                && na.isAdjoiningWeekoffEnabled() == current.isNaAdjoiningWeekoffEnabled()
                && Objects.equals(na.getAdjoiningWeekoffCondition(), current.getNaAdjoiningWeekoffCondition())
                && Objects.equals(na.getAdjoiningWeekoffCalendarDayThreshold(), current.getNaAdjoiningWeekoffCalendarDayThreshold())
                && na.isAdjoiningWeekoffIgnoreHalfDayLeave() == current.isNaAdjoiningWeekoffIgnoreHalfDayLeave()
                && la.isEnabled() == current.isLateArrivalEnabled()
                && Objects.equals(la.getBasis(), current.getLaBasis())
                && Objects.equals(la.getGracePeriodMinutes(), current.getLaGracePeriodMinutes())
                && Objects.equals(la.getExemptCount(), current.getLaExemptCount())
                && Objects.equals(la.getExemptPeriod(), current.getLaExemptPeriod())
                && bdEquals(la.getDeductionDays(), current.getLaDeductionDays())
                && Objects.equals(la.getDeductionPerShifts(), current.getLaDeductionPerShifts())
                && la.isIgnoreWhenEffectiveHoursMetEnabled() == current.isLaIgnoreWhenEffectiveHoursMetEnabled()
                && bdEquals(la.getAllowedHours(), current.getLaAllowedHours())
                && Objects.equals(la.getCombinedRuleBehavior(), current.getLaCombinedRuleBehavior())
                && la.isPenaliseWhenCausedByMissingLogEnabled() == current.isLaPenaliseWhenCausedByMissingLogEnabled()
                && whs.isEnabled() == current.isWorkHoursShortageEnabled()
                && Objects.equals(whs.getDeductionBasis(), current.getWhsDeductionBasis())
                && Objects.equals(whs.getDeductionPeriod(), current.getWhsDeductionPeriod())
                && whs.isApplyPenaltyForShortageEnabled() == current.isWhsApplyPenaltyForShortageEnabled()
                && whs.isApplyPenaltyForLateArrivalEnabled() == current.isWhsApplyPenaltyForLateArrivalEnabled()
                && whs.isExcludeHoursOutsideShiftEnabled() == current.isWhsExcludeHoursOutsideShiftEnabled()
                && whs.isPenalizeShortageCausedByMissingLogsEnabled() == current.isWhsPenalizeShortageCausedByMissingLogsEnabled()
                && ml.isEnabled() == current.isMissingLogsEnabled()
                && Objects.equals(ml.getExemptDays(), current.getMlExemptDays())
                && Objects.equals(ml.getExemptPeriod(), current.getMlExemptPeriod())
                && Objects.equals(ml.getDeductionMode(), current.getMlDeductionMode())
                && bdEquals(ml.getDeductionDays(), current.getMlDeductionDays())
                && Objects.equals(ml.getDeductionPerShifts(), current.getMlDeductionPerShifts())
                && ml.isIgnoreRuleEnabled() == current.isMlIgnoreRuleEnabled()
                && Objects.equals(ml.getIgnoreRuleThresholdPercent(), current.getMlIgnoreRuleThresholdPercent())
                && Objects.equals(b.getDeductionMethod(), current.getDeductionMethod())
                && Objects.equals(requestedLeavePriorityOrder, current.getLeavePriorityOrder())
                && Objects.equals(b.getBufferPeriodDays(), current.getBufferPeriodDays())
                && b.isNoticePeriodForcesLopEnabled() == current.isNoticePeriodForcesLopEnabled();
        if (!scalarsMatch) {
            return false;
        }

        List<PenalizationPolicyWorkHoursTier> existingTiers = tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(current.getId());
        if (whs.getTiers().size() != existingTiers.size()) {
            return false;
        }
        for (int i = 0; i < existingTiers.size(); i++) {
            if (!bdEquals(whs.getTiers().get(i).getThresholdPercent(), existingTiers.get(i).getThresholdPercent())
                    || !bdEquals(whs.getTiers().get(i).getDeductionDays(), existingTiers.get(i).getDeductionDays())) {
                return false;
            }
        }

        List<PenalizationPolicyLateHoursTier> existingLateHoursTiers =
                lateHoursTierRepository.findByPolicyVersionIdOrderBySortOrderAsc(current.getId());
        if (la.getLateHoursTiers().size() != existingLateHoursTiers.size()) {
            return false;
        }
        for (int i = 0; i < existingLateHoursTiers.size(); i++) {
            if (!bdEquals(la.getLateHoursTiers().get(i).getThresholdHours(), existingLateHoursTiers.get(i).getThresholdHours())
                    || !bdEquals(la.getLateHoursTiers().get(i).getDeductionDays(), existingLateHoursTiers.get(i).getDeductionDays())) {
                return false;
            }
        }
        return true;
    }

    private boolean bdEquals(java.math.BigDecimal a, java.math.BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    /**
     * Section 21: tier thresholds must be distinct — two rules for "less than 50%" is ambiguous.
     * Also requires at least one tier once the section is enabled: with none configured, Work
     * Hours Shortage would be "on" yet silently never match anything (the engine's tier lookup
     * always misses), which is a missing-configuration mistake, not a deliberate no-op policy.
     * Deliberately does NOT enforce that deduction amounts increase monotonically with severity —
     * {@code save_validDistinctTiers_accepted_savedInOrder} already exercises a shipped example
     * where they don't, so admins retain full freedom over each tier's amount independent of its
     * neighbors.
     */
    private void validateTiers(PenalizationPolicyRequest request) {
        WorkHoursShortageConfigDto whs = request.getWorkHoursShortage();
        List<WorkHoursTierDto> tiers = whs.getTiers();
        long distinctThresholds = tiers.stream().map(WorkHoursTierDto::getThresholdPercent).distinct().count();
        if (distinctThresholds != tiers.size()) {
            throw new IllegalArgumentException("Work Hours Shortage tier thresholds must be distinct");
        }
        boolean anyOver100 = tiers.stream().anyMatch(t -> t.getThresholdPercent().compareTo(new java.math.BigDecimal("100")) > 0);
        if (anyOver100) {
            throw new IllegalArgumentException("Work Hours Shortage tier thresholds cannot exceed 100%");
        }
        if (whs.isEnabled() && tiers.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one Work Hours Shortage tier must be configured when the section is enabled");
        }
    }

    /**
     * Section 31: total-late-hours tier thresholds must be distinct and non-negative, and at
     * least one must be configured once the TOTAL_HOURS basis is selected — otherwise that basis
     * would be "on" yet never actually able to match a tier (see {@link #validateTiers}'s same
     * reasoning for Work Hours Shortage).
     */
    private void validateLateHoursTiers(PenalizationPolicyRequest request) {
        LateArrivalConfigDto la = request.getLateArrival();
        List<LateHoursTierDto> tiers = la.getLateHoursTiers();
        long distinctThresholds = tiers.stream().map(LateHoursTierDto::getThresholdHours).distinct().count();
        if (distinctThresholds != tiers.size()) {
            throw new IllegalArgumentException("Total Late Hours tier thresholds must be distinct");
        }
        if (la.isEnabled() && "TOTAL_HOURS".equals(la.getBasis()) && tiers.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one Total Late Hours tier must be configured when basis is TOTAL_HOURS");
        }
    }
}
