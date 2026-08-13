package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.penalization.*;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.entity.PenalizationPolicyWorkHoursTier;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.PenalizationPolicyVersionRepository;
import com.nforce.onehr.repository.PenalizationPolicyWorkHoursTierRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
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
 */
@Service
@RequiredArgsConstructor
public class PenalizationPolicyService {

    private final PenalizationPolicyVersionRepository versionRepository;
    private final PenalizationPolicyWorkHoursTierRepository tierRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer snapshotSerializer;
    private final AttendanceProperties attendanceProperties;

    @Transactional(readOnly = true)
    public Optional<PenalizationPolicyResponse> getCurrent() {
        return versionRepository.findByEffectiveToIsNull().map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<PenalizationPolicyVersionSummary> getVersionHistory() {
        return versionRepository.findAllByOrderByVersionDesc().stream()
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
    public PenalizationPolicyResponse save(PenalizationPolicyRequest request, String actorEmail) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));

        Optional<PenalizationPolicyVersion> current = versionRepository.findByEffectiveToIsNull();
        UUID policyId = current.map(PenalizationPolicyVersion::getPolicyId).orElseGet(UUID::randomUUID);
        int nextVersion = current.map(v -> v.getVersion() + 1).orElse(1);

        LocalDateTime now = LocalDateTime.now(ZoneId.of(attendanceProperties.getZone()));
        LocalDateTime effectiveFrom = now.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay();

        // Close the version being superseded — a bookkeeping update to its temporal boundary
        // only, never to its configuration values (see EmployeeManagerHistory for the same
        // close-on-supersede convention already used elsewhere in this codebase).
        current.ifPresent(previous -> {
            previous.setEffectiveTo(effectiveFrom.minusNanos(1));
            versionRepository.save(previous);
        });

        PenalizationPolicyVersion version = PenalizationPolicyVersion.builder()
                .policyId(policyId)
                .version(nextVersion)
                .effectiveFrom(effectiveFrom)
                .noAttendanceEnabled(request.getNoAttendance().isEnabled())
                .naDeductionDays(request.getNoAttendance().getDeductionDays())
                .naNoShowEnabled(request.getNoAttendance().isNoShowEnabled())
                .naNoShowThresholdHours(request.getNoAttendance().getNoShowThresholdHours())
                .lateArrivalEnabled(request.getLateArrival().isEnabled())
                .laBasis(request.getLateArrival().getBasis())
                .laGracePeriodMinutes(request.getLateArrival().getGracePeriodMinutes())
                .laExemptCount(request.getLateArrival().getExemptCount())
                .laExemptPeriod(request.getLateArrival().getExemptPeriod())
                .laDeductionDays(request.getLateArrival().getDeductionDays())
                .laDeductionPerShifts(request.getLateArrival().getDeductionPerShifts())
                .laIgnoreWhenEffectiveHoursMetEnabled(request.getLateArrival().isIgnoreWhenEffectiveHoursMetEnabled())
                .workHoursShortageEnabled(request.getWorkHoursShortage().isEnabled())
                .whsDeductionBasis(request.getWorkHoursShortage().getDeductionBasis())
                .whsDeductionPeriod(request.getWorkHoursShortage().getDeductionPeriod())
                .whsApplyPenaltyForShortageEnabled(request.getWorkHoursShortage().isApplyPenaltyForShortageEnabled())
                .whsApplyPenaltyForLateArrivalEnabled(request.getWorkHoursShortage().isApplyPenaltyForLateArrivalEnabled())
                .missingLogsEnabled(request.getMissingLogs().isEnabled())
                .mlExemptDays(request.getMissingLogs().getExemptDays())
                .mlExemptPeriod(request.getMissingLogs().getExemptPeriod())
                .mlDeductionMode(request.getMissingLogs().getDeductionMode())
                .mlDeductionDays(request.getMissingLogs().getDeductionDays())
                .mlDeductionPerShifts(request.getMissingLogs().getDeductionPerShifts())
                .mlIgnoreRuleEnabled(request.getMissingLogs().isIgnoreRuleEnabled())
                .mlIgnoreRuleThresholdPercent(request.getMissingLogs().getIgnoreRuleThresholdPercent())
                .createdBy(actor.getId())
                .build();
        version = versionRepository.save(version);

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

        String action = current.isEmpty() ? "PENALIZATION_POLICY_CREATED" : "PENALIZATION_POLICY_UPDATED";
        String before = current.map(this::snapshotFields).map(snapshotSerializer::toJson).orElse(null);
        String after = snapshotSerializer.toJson(snapshotFields(version));
        auditService.log(actor.getId(), action, version.getId(), before, after);

        return toResponse(version);
    }

    private Map<String, Object> snapshotFields(PenalizationPolicyVersion v) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("version", v.getVersion());
        fields.put("effectiveFrom", v.getEffectiveFrom());
        fields.put("noAttendanceEnabled", v.isNoAttendanceEnabled());
        fields.put("naDeductionDays", v.getNaDeductionDays());
        fields.put("lateArrivalEnabled", v.isLateArrivalEnabled());
        fields.put("laGracePeriodMinutes", v.getLaGracePeriodMinutes());
        fields.put("laExemptCount", v.getLaExemptCount());
        fields.put("laDeductionDays", v.getLaDeductionDays());
        fields.put("workHoursShortageEnabled", v.isWorkHoursShortageEnabled());
        fields.put("missingLogsEnabled", v.isMissingLogsEnabled());
        fields.put("mlExemptDays", v.getMlExemptDays());
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

        NoAttendanceConfigDto noAttendance = new NoAttendanceConfigDto();
        noAttendance.setEnabled(v.isNoAttendanceEnabled());
        noAttendance.setDeductionDays(v.getNaDeductionDays());
        noAttendance.setNoShowEnabled(v.isNaNoShowEnabled());
        noAttendance.setNoShowThresholdHours(v.getNaNoShowThresholdHours());

        LateArrivalConfigDto lateArrival = new LateArrivalConfigDto();
        lateArrival.setEnabled(v.isLateArrivalEnabled());
        lateArrival.setBasis(v.getLaBasis());
        lateArrival.setGracePeriodMinutes(v.getLaGracePeriodMinutes());
        lateArrival.setExemptCount(v.getLaExemptCount());
        lateArrival.setExemptPeriod(v.getLaExemptPeriod());
        lateArrival.setDeductionDays(v.getLaDeductionDays());
        lateArrival.setDeductionPerShifts(v.getLaDeductionPerShifts());
        lateArrival.setIgnoreWhenEffectiveHoursMetEnabled(v.isLaIgnoreWhenEffectiveHoursMetEnabled());

        WorkHoursShortageConfigDto workHours = new WorkHoursShortageConfigDto();
        workHours.setEnabled(v.isWorkHoursShortageEnabled());
        workHours.setDeductionBasis(v.getWhsDeductionBasis());
        workHours.setDeductionPeriod(v.getWhsDeductionPeriod());
        workHours.setTiers(tiers);
        workHours.setApplyPenaltyForShortageEnabled(v.isWhsApplyPenaltyForShortageEnabled());
        workHours.setApplyPenaltyForLateArrivalEnabled(v.isWhsApplyPenaltyForLateArrivalEnabled());

        MissingLogsConfigDto missingLogs = new MissingLogsConfigDto();
        missingLogs.setEnabled(v.isMissingLogsEnabled());
        missingLogs.setExemptDays(v.getMlExemptDays());
        missingLogs.setExemptPeriod(v.getMlExemptPeriod());
        missingLogs.setDeductionMode(v.getMlDeductionMode());
        missingLogs.setDeductionDays(v.getMlDeductionDays());
        missingLogs.setDeductionPerShifts(v.getMlDeductionPerShifts());
        missingLogs.setIgnoreRuleEnabled(v.isMlIgnoreRuleEnabled());
        missingLogs.setIgnoreRuleThresholdPercent(v.getMlIgnoreRuleThresholdPercent());

        return PenalizationPolicyResponse.builder()
                .id(v.getId()).policyId(v.getPolicyId()).version(v.getVersion())
                .effectiveFrom(v.getEffectiveFrom()).effectiveTo(v.getEffectiveTo())
                .noAttendance(noAttendance).lateArrival(lateArrival)
                .workHoursShortage(workHours).missingLogs(missingLogs)
                .createdBy(v.getCreatedBy()).createdAt(v.getCreatedAt())
                .build();
    }
}
