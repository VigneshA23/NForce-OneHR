package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.penalization.*;
import com.nforce.onehr.entity.PenalisationPolicy;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.PenalisationPolicyRepository;
import com.nforce.onehr.repository.PenalizationPolicyVersionRepository;
import com.nforce.onehr.repository.PenalizationPolicyWorkHoursTierRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Versioning/immutability/audit behavior of Organization Masters -> Penalization Policy. */
@ExtendWith(MockitoExtension.class)
class PenalizationPolicyServiceTest {

    @Mock private PenalizationPolicyVersionRepository versionRepository;
    @Mock private PenalizationPolicyWorkHoursTierRepository tierRepository;
    @Mock private com.nforce.onehr.repository.PenalizationPolicyLateHoursTierRepository lateHoursTierRepository;
    @Mock private PenalisationPolicyRepository penalisationPolicyRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer snapshotSerializer;
    @Mock private AttendanceProperties attendanceProperties;
    @Mock private com.nforce.onehr.repository.EmployeeRepository employeeRepository;
    @Mock private NotificationService notificationService;

    private PenalizationPolicyService service;
    private final UUID actorId = UUID.randomUUID();
    private final UUID defaultPolicyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PenalizationPolicyService(versionRepository, tierRepository, lateHoursTierRepository,
                penalisationPolicyRepository, userRepository, auditService, snapshotSerializer, attendanceProperties,
                employeeRepository, notificationService);
        lenient().when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        lenient().when(userRepository.findByEmail("hr@test.com"))
                .thenReturn(Optional.of(User.builder().id(actorId).email("hr@test.com").build()));
        lenient().when(versionRepository.save(any())).thenAnswer(inv -> {
            PenalizationPolicyVersion v = inv.getArgument(0);
            if (v.getId() == null) v.setId(UUID.randomUUID());
            return v;
        });
        lenient().when(snapshotSerializer.toJson(any())).thenReturn("{}");
        lenient().when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        lenient().when(lateHoursTierRepository.findByPolicyVersionIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        // Only consulted when no current version exists yet (first-ever save) — see
        // PenalizationPolicyService.resolveDefaultPolicyId.
        lenient().when(penalisationPolicyRepository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(
                PenalisationPolicy.builder().id(defaultPolicyId)
                        .createdAt(java.time.LocalDateTime.of(2025, 1, 1, 0, 0)).build()));
    }

    private PenalizationPolicyRequest minimalRequest() {
        PenalizationPolicyRequest req = new PenalizationPolicyRequest();
        req.setBasicInfo(new BasicInfoConfigDto());
        req.setNoAttendance(new NoAttendanceConfigDto());
        LateArrivalConfigDto la = new LateArrivalConfigDto();
        la.setGracePeriodMinutes(10);
        req.setLateArrival(la);
        req.setWorkHoursShortage(new WorkHoursShortageConfigDto());
        req.setMissingLogs(new MissingLogsConfigDto());
        return req;
    }

    // ── Phase 3: Work Hours Shortage — basis, frequency, exclude-outside-shift, missing-log linkage ──

    @Test
    void save_workHoursShortagePhase3Settings_roundTripThroughToResponse() {
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());
        PenalizationPolicyRequest req = minimalRequest();
        req.getWorkHoursShortage().setEnabled(true);
        req.getWorkHoursShortage().setDeductionBasis("GROSS_HOURS");
        req.getWorkHoursShortage().setDeductionPeriod("WEEK");
        req.getWorkHoursShortage().setExcludeHoursOutsideShiftEnabled(true);
        req.getWorkHoursShortage().setPenalizeShortageCausedByMissingLogsEnabled(true);
        req.getWorkHoursShortage().setTiers(List.of(tier("90", "0.5")));

        PenalizationPolicyResponse response = service.save(null, req, "hr@test.com");

        assertEquals("GROSS_HOURS", response.getWorkHoursShortage().getDeductionBasis());
        assertEquals("WEEK", response.getWorkHoursShortage().getDeductionPeriod());
        assertEquals(true, response.getWorkHoursShortage().isExcludeHoursOutsideShiftEnabled());
        assertEquals(true, response.getWorkHoursShortage().isPenalizeShortageCausedByMissingLogsEnabled());
    }

    @Test
    void save_workHoursShortageDefaults_preserveBackwardCompatibleBehavior() {
        // A request that never touches the new Phase 3 fields must persist the exact same
        // defaults every existing policy already has: EFFECTIVE_HOURS/DAY, both toggles off.
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());

        PenalizationPolicyResponse response = service.save(null, minimalRequest(), "hr@test.com");

        assertEquals("EFFECTIVE_HOURS", response.getWorkHoursShortage().getDeductionBasis());
        assertEquals("DAY", response.getWorkHoursShortage().getDeductionPeriod());
        assertEquals(false, response.getWorkHoursShortage().isExcludeHoursOutsideShiftEnabled());
        assertEquals(false, response.getWorkHoursShortage().isPenalizeShortageCausedByMissingLogsEnabled());
    }

    @Test
    void firstSave_createsVersion1_andAuditsCreated() {
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());

        PenalizationPolicyResponse response = service.save(null, minimalRequest(), "hr@test.com");

        assertEquals(1, response.getVersion());
        assertEquals(10, response.getLateArrival().getGracePeriodMinutes());
        verify(auditService).log(eq(actorId), eq("PENALIZATION_POLICY_CREATED"), any(), isNull(), any());
    }

    @Test
    void secondSave_createsVersion2_closesVersion1_neverMutatesItsConfig() {
        PenalizationPolicyVersion v1 = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .lateArrivalEnabled(true).laGracePeriodMinutes(10)
                .effectiveFrom(java.time.LocalDateTime.of(2026, 8, 1, 0, 0))
                .build();
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(v1.getPolicyId())).thenReturn(Optional.of(v1));

        PenalizationPolicyRequest req = minimalRequest();
        req.getLateArrival().setGracePeriodMinutes(15);
        req.getLateArrival().setEnabled(true);
        PenalizationPolicyResponse v2Response = service.save(v1.getPolicyId(), req, "hr@test.com");

        assertEquals(2, v2Response.getVersion());
        assertEquals(15, v2Response.getLateArrival().getGracePeriodMinutes());
        assertEquals(v1.getPolicyId(), v2Response.getPolicyId(), "policyId must stay stable across versions");
        assertEquals(actorId, v2Response.getUpdatedBy());

        // V1's own grace period value is never rewritten — only effectiveTo (a temporal
        // boundary, not a configuration value) is set on it.
        assertEquals(10, v1.getLaGracePeriodMinutes());
        assertNotNull(v1.getEffectiveTo());
        assertEquals(actorId, v1.getUpdatedBy(), "closing v1 on supersede is itself a row mutation worth attributing");

        ArgumentCaptor<PenalizationPolicyVersion> savedV1 = ArgumentCaptor.forClass(PenalizationPolicyVersion.class);
        verify(versionRepository, times(2)).save(savedV1.capture());
        assertEquals(10, savedV1.getAllValues().get(0).getLaGracePeriodMinutes());

        verify(auditService).log(eq(actorId), eq("PENALIZATION_POLICY_UPDATED"), any(), any(), any());
    }

    @Test
    void getCurrent_whenNoVersionExists_isEmpty() {
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());

        assertTrue(service.getCurrent(null).isEmpty());
    }

    // ── Section 20: no-op save — identical resubmission must not version/audit/notify ────────

    /**
     * Builds a real, fully-defaulted "current" version by actually saving once — rather than
     * hand-building a {@link PenalizationPolicyVersion} in the test (easy to miss one of the many
     * DTO-defaulted fields, like {@code WorkHoursShortageConfigDto}'s {@code deductionBasis}
     * defaulting to {@code "EFFECTIVE_HOURS"} with no entity-side default to match), this exercises
     * {@link PenalizationPolicyService#save} itself so every default is guaranteed authentic.
     */
    private PenalizationPolicyVersion saveAndCaptureVersion(UUID policyId, PenalizationPolicyRequest request) {
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(policyId)).thenReturn(Optional.empty());
        service.save(policyId, request, "hr@test.com");
        ArgumentCaptor<PenalizationPolicyVersion> captor = ArgumentCaptor.forClass(PenalizationPolicyVersion.class);
        verify(versionRepository).save(captor.capture());
        clearInvocations(versionRepository, auditService, notificationService, tierRepository, lateHoursTierRepository);
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(policyId)).thenReturn(Optional.of(captor.getValue()));
        return captor.getValue();
    }

    @Test
    void save_identicalConfigurationAtTheSameDefaultDate_isANoOp() {
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        UUID policyId = UUID.randomUUID();
        PenalizationPolicyRequest original = minimalRequest();
        original.getLateArrival().setEnabled(true);
        PenalizationPolicyVersion current = saveAndCaptureVersion(policyId, original);
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(current.getId())).thenReturn(List.of());
        when(lateHoursTierRepository.findByPolicyVersionIdOrderBySortOrderAsc(current.getId())).thenReturn(List.of());

        PenalizationPolicyRequest resubmitted = minimalRequest();
        resubmitted.getLateArrival().setEnabled(true);
        PenalizationPolicyResponse response = service.save(policyId, resubmitted, "hr@test.com");

        assertEquals(current.getId(), response.getId());
        verify(versionRepository, never()).save(any());
        verifyNoInteractions(auditService);
        verifyNoInteractions(notificationService);
    }

    /**
     * The default "1st of next month" date can coincidentally land on an already-scheduled
     * pending version's own date (an explicit date can't — validateBasicInfo requires strictly
     * after). When the content genuinely differs, this is a content-only edit of that same
     * pending version done in place — same id/version number, never a new point in the version
     * chain — since closing it would set its effectiveTo one nanosecond before its own
     * effectiveFrom (Section 4: no negative-length ranges).
     */
    @Test
    void save_sameDateButDifferentRuleContent_editsThatPendingVersionInPlace() {
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        java.time.LocalDateTime defaultEffectiveFrom = today.withDayOfMonth(1).plusMonths(1).atStartOfDay();
        UUID originalCreator = UUID.randomUUID();
        java.time.LocalDateTime originalCreatedAt = java.time.LocalDateTime.of(2026, 1, 1, 0, 0);
        PenalizationPolicyVersion current = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(4)
                .effectiveFrom(defaultEffectiveFrom)
                .lateArrivalEnabled(true).laGracePeriodMinutes(10)
                .deductionMethod("LOSS_OF_PAY")
                .createdBy(originalCreator).createdAt(originalCreatedAt)
                .build();
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(current.getPolicyId())).thenReturn(Optional.of(current));
        PenalizationPolicyRequest req = minimalRequest();
        req.getLateArrival().setEnabled(true);
        req.getLateArrival().setGracePeriodMinutes(20); // the actual change

        PenalizationPolicyResponse response = service.save(current.getPolicyId(), req, "hr@test.com");

        assertEquals(current.getId(), response.getId());
        assertEquals(4, response.getVersion(), "same version number — this is an edit, not a new point in the chain");
        assertEquals(20, response.getLateArrival().getGracePeriodMinutes());
        assertEquals(originalCreator, response.getCreatedBy());
        assertEquals(originalCreatedAt, response.getCreatedAt());
        assertEquals(actorId, response.getUpdatedBy(), "createdBy/createdAt are preserved, but updatedBy reflects whoever made this edit");
        assertNull(current.getEffectiveTo(), "never closed — there is no distinct previous version being superseded");
        verify(versionRepository, times(1)).save(any());
        verify(auditService).log(eq(actorId), eq("PENALIZATION_POLICY_UPDATED"), any(), any(), any());
    }

    @Test
    void save_sameDateAndScalarsButTiersChanged_editsThatPendingVersionInPlace() {
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        java.time.LocalDateTime defaultEffectiveFrom = today.withDayOfMonth(1).plusMonths(1).atStartOfDay();
        PenalizationPolicyVersion current = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(defaultEffectiveFrom)
                .workHoursShortageEnabled(true)
                .deductionMethod("LOSS_OF_PAY")
                .build();
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(current.getPolicyId())).thenReturn(Optional.of(current));
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(current.getId())).thenReturn(List.of());
        PenalizationPolicyRequest req = minimalRequest();
        req.getWorkHoursShortage().setEnabled(true);
        req.getWorkHoursShortage().setTiers(List.of(tier("50", "1"))); // current has zero tiers

        PenalizationPolicyResponse response = service.save(current.getPolicyId(), req, "hr@test.com");

        assertEquals(1, response.getVersion());
        assertEquals(current.getId(), response.getId());
        verify(tierRepository).deleteByPolicyVersionId(current.getId());
        verify(auditService).log(eq(actorId), eq("PENALIZATION_POLICY_UPDATED"), any(), any(), any());
    }

    @Test
    void save_differentScaleButNumericallyEqualAmount_stillTreatedAsUnchanged() {
        // BigDecimal("0.50") vs BigDecimal("0.5") differ under Object#equals but are numerically
        // identical — a request round-tripped through JSON could easily produce either scale.
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        UUID policyId = UUID.randomUUID();
        PenalizationPolicyRequest original = minimalRequest();
        original.getLateArrival().setEnabled(true);
        original.getLateArrival().setDeductionDays(new java.math.BigDecimal("0.50"));
        PenalizationPolicyVersion current = saveAndCaptureVersion(policyId, original);
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(current.getId())).thenReturn(List.of());
        when(lateHoursTierRepository.findByPolicyVersionIdOrderBySortOrderAsc(current.getId())).thenReturn(List.of());

        PenalizationPolicyRequest resubmitted = minimalRequest();
        resubmitted.getLateArrival().setEnabled(true);
        resubmitted.getLateArrival().setDeductionDays(new java.math.BigDecimal("0.5"));
        service.save(policyId, resubmitted, "hr@test.com");

        verify(versionRepository, never()).save(any());
    }

    private WorkHoursTierDto tier(String thresholdPercent, String deductionDays) {
        WorkHoursTierDto t = new WorkHoursTierDto();
        t.setThresholdPercent(new java.math.BigDecimal(thresholdPercent));
        t.setDeductionDays(new java.math.BigDecimal(deductionDays));
        return t;
    }

    // ── Section 21: Work Hours Shortage tier validation (PenalizationPolicyService.validateTiers) ──

    @Test
    void save_duplicateTierThresholds_rejected_doesNotSaveVersion() {
        PenalizationPolicyRequest req = minimalRequest();
        req.getWorkHoursShortage().setTiers(List.of(
                tier("20", "1"),
                tier("20", "2")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.save(null, req, "hr@test.com"));

        assertEquals("Work Hours Shortage tier thresholds must be distinct", ex.getMessage());
        verify(versionRepository, never()).save(any());
        verifyNoInteractions(tierRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void save_tierThresholdOver100_rejected_doesNotSaveVersion() {
        PenalizationPolicyRequest req = minimalRequest();
        req.getWorkHoursShortage().setTiers(List.of(
                tier("101", "1")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.save(null, req, "hr@test.com"));

        assertEquals("Work Hours Shortage tier thresholds cannot exceed 100%", ex.getMessage());
        verify(versionRepository, never()).save(any());
        verifyNoInteractions(tierRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void save_validDistinctTiers_accepted_savedInOrder() {
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());
        PenalizationPolicyRequest req = minimalRequest();
        req.getWorkHoursShortage().setTiers(List.of(
                tier("20", "1"),
                tier("50", "2"),
                tier("80", "3")));

        PenalizationPolicyResponse response = service.save(null, req, "hr@test.com");

        assertEquals(1, response.getVersion(), "validation passing must not change ordinary first-save behavior");
        verify(auditService).log(eq(actorId), eq("PENALIZATION_POLICY_CREATED"), any(), isNull(), any());

        ArgumentCaptor<com.nforce.onehr.entity.PenalizationPolicyWorkHoursTier> tierCaptor =
                ArgumentCaptor.forClass(com.nforce.onehr.entity.PenalizationPolicyWorkHoursTier.class);
        verify(tierRepository, times(3)).save(tierCaptor.capture());
        List<com.nforce.onehr.entity.PenalizationPolicyWorkHoursTier> savedTiers = tierCaptor.getAllValues();
        assertEquals(new java.math.BigDecimal("20"), savedTiers.get(0).getThresholdPercent());
        assertEquals(new java.math.BigDecimal("50"), savedTiers.get(1).getThresholdPercent());
        assertEquals(new java.math.BigDecimal("80"), savedTiers.get(2).getThresholdPercent());
    }

    @Test
    void save_workHoursShortageEnabledWithNoTiers_rejected_doesNotSaveVersion() {
        PenalizationPolicyRequest req = minimalRequest();
        req.getWorkHoursShortage().setEnabled(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.save(null, req, "hr@test.com"));

        assertEquals("At least one Work Hours Shortage tier must be configured when the section is enabled", ex.getMessage());
        verify(versionRepository, never()).save(any());
    }

    @Test
    void save_workHoursShortageDisabledWithNoTiers_accepted() {
        // Disabled sections need no tiers at all — the requirement only kicks in once enabled.
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());
        PenalizationPolicyRequest req = minimalRequest();

        PenalizationPolicyResponse response = service.save(null, req, "hr@test.com");

        assertEquals(1, response.getVersion());
    }

    private LateHoursTierDto lateHoursTier(String thresholdHours, String deductionDays) {
        LateHoursTierDto t = new LateHoursTierDto();
        t.setThresholdHours(new java.math.BigDecimal(thresholdHours));
        t.setDeductionDays(new java.math.BigDecimal(deductionDays));
        return t;
    }

    @Test
    void save_lateArrivalTotalHoursBasisWithNoTiers_rejected_doesNotSaveVersion() {
        PenalizationPolicyRequest req = minimalRequest();
        req.getLateArrival().setEnabled(true);
        req.getLateArrival().setBasis("TOTAL_HOURS");
        req.getLateArrival().setAllowedHours(new java.math.BigDecimal("2"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.save(null, req, "hr@test.com"));

        assertEquals("At least one Total Late Hours tier must be configured when basis is TOTAL_HOURS", ex.getMessage());
        verify(versionRepository, never()).save(any());
    }

    @Test
    void save_lateArrivalTotalHoursBasisWithTiers_accepted() {
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());
        PenalizationPolicyRequest req = minimalRequest();
        req.getLateArrival().setEnabled(true);
        req.getLateArrival().setBasis("TOTAL_HOURS");
        req.getLateArrival().setAllowedHours(new java.math.BigDecimal("2"));
        req.getLateArrival().setLateHoursTiers(List.of(lateHoursTier("2", "0.5"), lateHoursTier("5", "1")));

        PenalizationPolicyResponse response = service.save(null, req, "hr@test.com");

        assertEquals(1, response.getVersion());
    }

    // ── Section 45: mid-cycle policy-change notification ────────────────────────────────────

    @Test
    void secondSave_notifiesEveryEmployeeExplicitlyAssignedToThePolicy() {
        PenalizationPolicyVersion v1 = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(java.time.LocalDateTime.of(2026, 8, 1, 0, 0)).build();
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(v1.getPolicyId())).thenReturn(Optional.of(v1));
        UUID emp1 = UUID.randomUUID();
        UUID emp2 = UUID.randomUUID();
        when(employeeRepository.findByPenalisationPolicy_Id(v1.getPolicyId())).thenReturn(List.of(
                com.nforce.onehr.entity.Employee.builder().userId(emp1).fullName("Employee One").build(),
                com.nforce.onehr.entity.Employee.builder().userId(emp2).fullName("Employee Two").build()));

        service.save(v1.getPolicyId(), minimalRequest(), "hr@test.com");

        verify(notificationService).send(eq(emp1), eq("PENALIZATION_POLICY_CHANGED"), any(), any(), any());
        verify(notificationService).send(eq(emp2), eq("PENALIZATION_POLICY_CHANGED"), any(), any(), any());
    }

    @Test
    void firstSave_neverNotifies_noOneWasRelyingOnPriorBehaviorYet() {
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());

        service.save(null, minimalRequest(), "hr@test.com");

        verifyNoInteractions(notificationService);
    }

    // ── Section 15/25: admin-chosen future effective date ───────────────────────────────────

    @Test
    void save_requestedEffectiveFrom_inTheFuture_isUsedAsTheVersionsEffectiveDate() {
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        PenalizationPolicyRequest req = minimalRequest();
        java.time.LocalDate futureDate = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusMonths(2);
        req.getBasicInfo().setRequestedEffectiveFrom(futureDate);

        PenalizationPolicyResponse response = service.save(null, req, "hr@test.com");

        assertEquals(futureDate.atStartOfDay(), response.getEffectiveFrom());
    }

    @Test
    void save_requestedEffectiveFrom_today_rejected() {
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        PenalizationPolicyRequest req = minimalRequest();
        req.getBasicInfo().setRequestedEffectiveFrom(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.save(null, req, "hr@test.com"));

        assertEquals("Effective date must be in the future", ex.getMessage());
        verify(versionRepository, never()).save(any());
    }

    @Test
    void save_requestedEffectiveFrom_inThePast_rejected() {
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        PenalizationPolicyRequest req = minimalRequest();
        req.getBasicInfo().setRequestedEffectiveFrom(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(1));

        assertThrows(IllegalArgumentException.class, () -> service.save(null, req, "hr@test.com"));
        verify(versionRepository, never()).save(any());
    }

    @Test
    void save_requestedEffectiveFrom_notAfterAlreadyScheduledVersion_rejected() {
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        PenalizationPolicyVersion alreadyScheduled = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusMonths(2).atStartOfDay())
                .build();
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(alreadyScheduled.getPolicyId()))
                .thenReturn(Optional.of(alreadyScheduled));
        PenalizationPolicyRequest req = minimalRequest();
        // Same date as the already-scheduled version — not strictly after it.
        req.getBasicInfo().setRequestedEffectiveFrom(alreadyScheduled.getEffectiveFrom().toLocalDate());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.save(alreadyScheduled.getPolicyId(), req, "hr@test.com"));

        assertTrue(ex.getMessage().contains("must be after the currently scheduled version's effective date"));
        verify(versionRepository, never()).save(any());
    }

    @Test
    void save_noRequestedEffectiveFrom_defaultsToFirstOfNextMonth_unchangedBehavior() {
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");

        PenalizationPolicyResponse response = service.save(null, minimalRequest(), "hr@test.com");

        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        java.time.LocalDateTime expected = today.withDayOfMonth(1).plusMonths(1).atStartOfDay();
        assertEquals(expected, response.getEffectiveFrom());
    }

    @Test
    void save_lateArrivalIncidentsBasisWithNoTiers_accepted() {
        // The tier requirement is specific to TOTAL_HOURS — the default NUMBER_OF_INCIDENTS basis
        // needs no tiers at all.
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());
        PenalizationPolicyRequest req = minimalRequest();
        req.getLateArrival().setEnabled(true);

        PenalizationPolicyResponse response = service.save(null, req, "hr@test.com");

        assertEquals(1, response.getVersion());
    }
}
