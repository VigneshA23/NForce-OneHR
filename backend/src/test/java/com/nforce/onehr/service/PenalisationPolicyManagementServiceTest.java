package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.penalization.ClonePenalisationPolicyRequest;
import com.nforce.onehr.dto.penalization.CreatePenalisationPolicyRequest;
import com.nforce.onehr.dto.penalization.PenalisationPolicySummaryDto;
import com.nforce.onehr.dto.penalization.RenamePenalisationPolicyRequest;
import com.nforce.onehr.entity.PenalisationPolicy;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.PenalisationPolicyRepository;
import com.nforce.onehr.repository.PenalizationPolicyAllocationRepository;
import com.nforce.onehr.repository.PenalizationPolicyLateHoursTierRepository;
import com.nforce.onehr.repository.PenalizationPolicyVersionRepository;
import com.nforce.onehr.repository.PenalizationPolicyWorkHoursTierRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Section 5: Policy List — create/rename/clone/delete for the PenalisationPolicy label entity. */
@ExtendWith(MockitoExtension.class)
class PenalisationPolicyManagementServiceTest {

    @Mock private PenalisationPolicyRepository penalisationPolicyRepository;
    @Mock private PenalizationPolicyVersionRepository versionRepository;
    @Mock private PenalizationPolicyWorkHoursTierRepository tierRepository;
    @Mock private PenalizationPolicyLateHoursTierRepository lateHoursTierRepository;
    @Mock private PenalizationPolicyAllocationRepository allocationRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private AttendanceProperties attendanceProperties;
    @Mock private PenalizationPolicyResolutionService resolutionService;

    private PenalisationPolicyManagementService service;
    private final UUID actorId = UUID.randomUUID();
    private final String hrEmail = "hr@test.com";

    @BeforeEach
    void setUp() {
        service = new PenalisationPolicyManagementService(penalisationPolicyRepository, versionRepository,
                tierRepository, lateHoursTierRepository, allocationRepository, userRepository,
                auditService, auditSnapshot, attendanceProperties, resolutionService);
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        lenient().when(allocationRepository.countByPenalisationPolicyId(any())).thenReturn(0L);
        lenient().when(resolutionService.resolveCurrentEmployeeCount(any(), any())).thenReturn(0L);
        lenient().when(resolutionService.resolveCurrentEmployeeCountsByPolicy(any())).thenReturn(java.util.Map.of());
        lenient().when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(User.builder().id(actorId).email(hrEmail).build()));
        lenient().when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        lenient().when(penalisationPolicyRepository.save(any())).thenAnswer(inv -> {
            PenalisationPolicy p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
    }

    @Test
    void create_savesNewPolicy_andAudits() {
        when(penalisationPolicyRepository.findByName("Field Sales Policy")).thenReturn(Optional.empty());
        CreatePenalisationPolicyRequest req = new CreatePenalisationPolicyRequest();
        req.setName("Field Sales Policy");
        req.setDescription("For field sales reps");

        PenalisationPolicySummaryDto result = service.create(req, hrEmail);

        assertEquals("Field Sales Policy", result.getName());
        assertEquals("ACTIVE", result.getStatus());
        assertEquals(0, result.getEmployeeCount());
        assertNull(result.getCurrentVersion(), "brand new policy has no rule configuration yet");
        verify(auditService).log(actorId, "PENALISATION_POLICY_CREATED", result.getId());
    }

    @Test
    void create_duplicateName_throws() {
        when(penalisationPolicyRepository.findByName("Existing")).thenReturn(Optional.of(
                PenalisationPolicy.builder().id(UUID.randomUUID()).name("Existing").build()));
        CreatePenalisationPolicyRequest req = new CreatePenalisationPolicyRequest();
        req.setName("Existing");

        assertThrows(IllegalStateException.class, () -> service.create(req, hrEmail));
    }

    @Test
    void rename_updatesNameAndAudits() {
        UUID id = UUID.randomUUID();
        PenalisationPolicy policy = PenalisationPolicy.builder().id(id).name("Old Name").status("ACTIVE").build();
        when(penalisationPolicyRepository.findById(id)).thenReturn(Optional.of(policy));
        when(penalisationPolicyRepository.findByName("New Name")).thenReturn(Optional.empty());
        RenamePenalisationPolicyRequest req = new RenamePenalisationPolicyRequest();
        req.setName("New Name");

        PenalisationPolicySummaryDto result = service.rename(id, req, hrEmail);

        assertEquals("New Name", result.getName());
        verify(auditService).log(actorId, "PENALISATION_POLICY_RENAMED", id, "{}", "{}");
    }

    @Test
    void toggleActive_deactivatesAnActivePolicy_butLeavesItsCountAlone() {
        UUID id = UUID.randomUUID();
        PenalisationPolicy policy = PenalisationPolicy.builder().id(id).name("Seasonal Policy").status("ACTIVE").build();
        when(penalisationPolicyRepository.findById(id)).thenReturn(Optional.of(policy));
        when(resolutionService.resolveCurrentEmployeeCount(eq(id), any())).thenReturn(4L);

        PenalisationPolicySummaryDto result = service.toggleActive(id, hrEmail);

        assertEquals("INACTIVE", result.getStatus());
        assertEquals(4, result.getEmployeeCount(), "deactivating must not touch who currently resolves to this policy");
        verify(auditService).log(actorId, "PENALISATION_POLICY_STATUS_CHANGED", id, "{}", "{}");
    }

    @Test
    void toggleActive_reactivatesAnInactivePolicy() {
        UUID id = UUID.randomUUID();
        PenalisationPolicy policy = PenalisationPolicy.builder().id(id).name("Retired Policy").status("INACTIVE").build();
        when(penalisationPolicyRepository.findById(id)).thenReturn(Optional.of(policy));

        PenalisationPolicySummaryDto result = service.toggleActive(id, hrEmail);

        assertEquals("ACTIVE", result.getStatus());
        verify(auditService).log(actorId, "PENALISATION_POLICY_STATUS_CHANGED", id, "{}", "{}");
    }

    @Test
    void clone_copiesCurrentVersionConfig_intoNewIndependentPolicy() {
        UUID sourceId = UUID.randomUUID();
        PenalisationPolicy source = PenalisationPolicy.builder().id(sourceId).name("Source").status("ACTIVE").build();
        when(penalisationPolicyRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(penalisationPolicyRepository.findByName("Clone of Source")).thenReturn(Optional.empty());
        PenalizationPolicyVersion sourceVersion = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(sourceId).version(3)
                .effectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0))
                .lateArrivalEnabled(true).laGracePeriodMinutes(10).laDeductionDays(new java.math.BigDecimal("0.5"))
                .build();
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(sourceId)).thenReturn(Optional.of(sourceVersion));
        when(versionRepository.save(any())).thenAnswer(inv -> {
            PenalizationPolicyVersion v = inv.getArgument(0);
            if (v.getId() == null) v.setId(UUID.randomUUID());
            return v;
        });
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(sourceVersion.getId())).thenReturn(List.of());
        when(lateHoursTierRepository.findByPolicyVersionIdOrderBySortOrderAsc(sourceVersion.getId())).thenReturn(List.of());

        ClonePenalisationPolicyRequest req = new ClonePenalisationPolicyRequest();
        req.setName("Clone of Source");

        PenalisationPolicySummaryDto result = service.clone(sourceId, req, hrEmail);

        assertEquals("Clone of Source", result.getName());
        assertNotEquals(sourceId, result.getId(), "clone is an independent policy, not a version of the source");
        verify(auditService).log(actorId, "PENALISATION_POLICY_CLONED", result.getId(), "{}", null);
    }

    @Test
    void clone_copiesPhase3WorkHoursShortageSettings_gross_frequency_excludeOutsideShift_missingLogLinkage() {
        UUID sourceId = UUID.randomUUID();
        PenalisationPolicy source = PenalisationPolicy.builder().id(sourceId).name("Source").status("ACTIVE").build();
        when(penalisationPolicyRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(penalisationPolicyRepository.findByName("Clone of Source")).thenReturn(Optional.empty());
        PenalizationPolicyVersion sourceVersion = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(sourceId).version(1)
                .effectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0))
                .workHoursShortageEnabled(true).whsDeductionBasis("GROSS_HOURS").whsDeductionPeriod("MONTH")
                .whsExcludeHoursOutsideShiftEnabled(true).whsPenalizeShortageCausedByMissingLogsEnabled(true)
                .build();
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(sourceId)).thenReturn(Optional.of(sourceVersion));
        org.mockito.ArgumentCaptor<PenalizationPolicyVersion> savedCaptor = org.mockito.ArgumentCaptor.forClass(PenalizationPolicyVersion.class);
        when(versionRepository.save(savedCaptor.capture())).thenAnswer(inv -> {
            PenalizationPolicyVersion v = inv.getArgument(0);
            if (v.getId() == null) v.setId(UUID.randomUUID());
            return v;
        });
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(sourceVersion.getId())).thenReturn(List.of());
        when(lateHoursTierRepository.findByPolicyVersionIdOrderBySortOrderAsc(sourceVersion.getId())).thenReturn(List.of());

        ClonePenalisationPolicyRequest req = new ClonePenalisationPolicyRequest();
        req.setName("Clone of Source");

        service.clone(sourceId, req, hrEmail);

        PenalizationPolicyVersion cloned = savedCaptor.getValue();
        assertEquals("GROSS_HOURS", cloned.getWhsDeductionBasis());
        assertEquals("MONTH", cloned.getWhsDeductionPeriod());
        assertEquals(true, cloned.isWhsExcludeHoursOutsideShiftEnabled());
        assertEquals(true, cloned.isWhsPenalizeShortageCausedByMissingLogsEnabled());
    }

    @Test
    void clone_thenModifyingTheClone_neverMutatesTheSourceVersion() {
        UUID sourceId = UUID.randomUUID();
        UUID cloneId = UUID.randomUUID();
        PenalisationPolicy source = PenalisationPolicy.builder().id(sourceId).name("Policy A").status("ACTIVE").build();
        when(penalisationPolicyRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(penalisationPolicyRepository.findByName("Policy B")).thenReturn(Optional.empty());
        PenalizationPolicyVersion sourceVersion = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(sourceId).version(1)
                .effectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0))
                .lateArrivalEnabled(true).laGracePeriodMinutes(10).laDeductionDays(new java.math.BigDecimal("0.5"))
                .noAttendanceEnabled(true).naDeductionDays(java.math.BigDecimal.ONE)
                .build();
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(sourceId)).thenReturn(Optional.of(sourceVersion));
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(sourceVersion.getId())).thenReturn(List.of());
        when(lateHoursTierRepository.findByPolicyVersionIdOrderBySortOrderAsc(sourceVersion.getId())).thenReturn(List.of());

        org.mockito.ArgumentCaptor<PenalizationPolicyVersion> savedCaptor = org.mockito.ArgumentCaptor.forClass(PenalizationPolicyVersion.class);
        when(versionRepository.save(savedCaptor.capture())).thenAnswer(inv -> {
            PenalizationPolicyVersion v = inv.getArgument(0);
            if (v.getId() == null) v.setId(cloneId);
            return v;
        });

        ClonePenalisationPolicyRequest req = new ClonePenalisationPolicyRequest();
        req.setName("Policy B");
        service.clone(sourceId, req, hrEmail);

        PenalizationPolicyVersion clonedVersion = savedCaptor.getValue();
        assertNotSame(sourceVersion, clonedVersion, "clone must be a distinct object, not a reference to the source's version");
        assertEquals(sourceId, sourceVersion.getPolicyId(), "source's own policyId is untouched");

        // Mutate the clone in place (simulating what a later PenalizationPolicyService.save(cloneId, ...) would build) —
        // the source object, and the value it reported at clone time, must remain exactly as before.
        clonedVersion.setLaGracePeriodMinutes(999);
        clonedVersion.setLaDeductionDays(new java.math.BigDecimal("5"));
        clonedVersion.setNoAttendanceEnabled(false);

        assertEquals(10, sourceVersion.getLaGracePeriodMinutes(), "modifying the clone must not change Policy A's grace period");
        assertEquals(new java.math.BigDecimal("0.5"), sourceVersion.getLaDeductionDays(), "modifying the clone must not change Policy A's deduction");
        assertTrue(sourceVersion.isNoAttendanceEnabled(), "modifying the clone must not change Policy A's No Attendance flag");
    }

    @Test
    void delete_blockedWhenEmployeesAssigned() {
        UUID id = UUID.randomUUID();
        when(penalisationPolicyRepository.findById(id)).thenReturn(Optional.of(
                PenalisationPolicy.builder().id(id).name("In Use").build()));
        when(resolutionService.resolveCurrentEmployeeCount(eq(id), any())).thenReturn(3L);

        assertThrows(IllegalStateException.class, () -> service.delete(id, hrEmail));
        verify(penalisationPolicyRepository, never()).delete(any());
    }

    @Test
    void delete_blockedWhenAllocationRecordsReferenceIt() {
        UUID id = UUID.randomUUID();
        when(penalisationPolicyRepository.findById(id)).thenReturn(Optional.of(
                PenalisationPolicy.builder().id(id).name("Allocated Elsewhere").build()));
        when(allocationRepository.countByPenalisationPolicyId(id)).thenReturn(2L);

        assertThrows(IllegalStateException.class, () -> service.delete(id, hrEmail));
        verify(penalisationPolicyRepository, never()).delete(any());
    }

    @Test
    void delete_blockedWhenOnlyRemainingPolicy() {
        UUID id = UUID.randomUUID();
        when(penalisationPolicyRepository.findById(id)).thenReturn(Optional.of(
                PenalisationPolicy.builder().id(id).name("Only One").build()));
        when(penalisationPolicyRepository.count()).thenReturn(1L);

        assertThrows(IllegalStateException.class, () -> service.delete(id, hrEmail));
        verify(penalisationPolicyRepository, never()).delete(any());
    }

    @Test
    void delete_removesPolicyAndItsVersions_whenUnassigned() {
        UUID id = UUID.randomUUID();
        PenalisationPolicy policy = PenalisationPolicy.builder().id(id).name("Unused").build();
        when(penalisationPolicyRepository.findById(id)).thenReturn(Optional.of(policy));
        when(penalisationPolicyRepository.count()).thenReturn(2L);
        PenalizationPolicyVersion v1 = PenalizationPolicyVersion.builder().id(UUID.randomUUID()).policyId(id).version(1).build();
        when(versionRepository.findByPolicyIdOrderByVersionDesc(id)).thenReturn(List.of(v1));
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(v1.getId())).thenReturn(List.of());
        when(lateHoursTierRepository.findByPolicyVersionIdOrderBySortOrderAsc(v1.getId())).thenReturn(List.of());

        service.delete(id, hrEmail);

        verify(versionRepository).delete(v1);
        verify(penalisationPolicyRepository).delete(policy);
        verify(auditService).log(actorId, "PENALISATION_POLICY_DELETED", id, "{}", null);
    }

    // Regression test for the "policy cannot be deleted/deactivated" bug: the audit_log table's
    // before_state/after_state columns are JSONB (see V1__create_users_and_roles.sql). Passing a
    // raw, unencoded string (a plain policy name, "ACTIVE"/"INACTIVE", or a bare UUID) as those
    // arguments makes Postgres reject the INSERT with "invalid input syntax for type json" — and
    // because that happens inside AuditService's own REQUIRES_NEW transaction, the failure marks
    // that transaction rollback-only even though AuditService catches the exception, so the outer
    // @Transactional delete()/toggleActive() call also gets rolled back with an
    // UnexpectedRollbackException — the policy status/deletion silently never took effect. Unlike
    // the mock-based tests above (which stub AuditSnapshotSerializer and can't detect this class of
    // bug), this test wires in the REAL serializer and asserts the captured before/after strings
    // are actually valid, parseable JSON — exactly what a JSONB column requires.
    @Test
    void toggleActiveAndDelete_auditSnapshots_areValidJson() throws Exception {
        AuditSnapshotSerializer realSerializer = new AuditSnapshotSerializer(new com.fasterxml.jackson.databind.ObjectMapper());
        PenalisationPolicyManagementService realService = new PenalisationPolicyManagementService(
                penalisationPolicyRepository, versionRepository, tierRepository, lateHoursTierRepository,
                allocationRepository, userRepository, auditService, realSerializer, attendanceProperties, resolutionService);
        com.fasterxml.jackson.databind.ObjectMapper reader = new com.fasterxml.jackson.databind.ObjectMapper();

        UUID toggleId = UUID.randomUUID();
        PenalisationPolicy toToggle = PenalisationPolicy.builder().id(toggleId).name("Field Policy").status("ACTIVE").build();
        when(penalisationPolicyRepository.findById(toggleId)).thenReturn(Optional.of(toToggle));
        realService.toggleActive(toggleId, hrEmail);
        org.mockito.ArgumentCaptor<String> toggleBefore = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> toggleAfter = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq(actorId), eq("PENALISATION_POLICY_STATUS_CHANGED"), eq(toggleId), toggleBefore.capture(), toggleAfter.capture());
        assertDoesNotThrow(() -> reader.readTree(toggleBefore.getValue()), "before_state must be valid JSON for the JSONB column");
        assertDoesNotThrow(() -> reader.readTree(toggleAfter.getValue()), "after_state must be valid JSON for the JSONB column");

        UUID deleteId = UUID.randomUUID();
        PenalisationPolicy toDelete = PenalisationPolicy.builder().id(deleteId).name("Unused Policy").build();
        when(penalisationPolicyRepository.findById(deleteId)).thenReturn(Optional.of(toDelete));
        when(penalisationPolicyRepository.count()).thenReturn(2L);
        when(versionRepository.findByPolicyIdOrderByVersionDesc(deleteId)).thenReturn(List.of());
        realService.delete(deleteId, hrEmail);
        org.mockito.ArgumentCaptor<String> deleteBefore = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq(actorId), eq("PENALISATION_POLICY_DELETED"), eq(deleteId), deleteBefore.capture(), eq(null));
        assertDoesNotThrow(() -> reader.readTree(deleteBefore.getValue()), "before_state must be valid JSON for the JSONB column");
    }
}
