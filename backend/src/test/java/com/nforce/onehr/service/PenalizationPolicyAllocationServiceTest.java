package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.assignments.AssignmentBulkResultResponse;
import com.nforce.onehr.dto.penalization.AllocationDto;
import com.nforce.onehr.dto.penalization.BulkAllocationRequest;
import com.nforce.onehr.dto.penalization.BulkRemoveAllocationRequest;
import com.nforce.onehr.dto.penalization.CreateAllocationRequest;
import com.nforce.onehr.dto.penalization.UpdateAllocationRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.PenalisationPolicy;
import com.nforce.onehr.entity.PenalizationPolicyAllocation;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PenalisationPolicyRepository;
import com.nforce.onehr.repository.PenalizationPolicyAllocationRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Penalization Policy Allocation: overlap prevention (reject, never silently truncate/delete),
 * CURRENT/FUTURE/HISTORICAL status gating on edit/remove, and per-row bulk success/failure
 * isolation — mirrors EmployeeAssignmentServiceTest's "one bad row never blocks the rest" contract.
 */
@ExtendWith(MockitoExtension.class)
class PenalizationPolicyAllocationServiceTest {

    @Mock private PenalizationPolicyAllocationRepository allocationRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private PenalisationPolicyRepository penalisationPolicyRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private PenalizationPolicyResolutionService resolutionService;
    @Mock private EmployeeService employeeService;
    @Mock private AttendanceProperties attendanceProperties;

    private PenalizationPolicyAllocationService service;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "hr@test.com";
    private final UUID employeeId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @BeforeEach
    void setUp() {
        service = new PenalizationPolicyAllocationService(allocationRepository, employeeRepository,
                penalisationPolicyRepository, userRepository, auditService, notificationService,
                auditSnapshot, resolutionService, employeeService, attendanceProperties);
        lenient().when(employeeService.findCurrentManagersBulk(any())).thenReturn(java.util.Map.of());

        lenient().when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        lenient().when(userRepository.findByEmail(actorEmail))
                .thenReturn(Optional.of(User.builder().id(actorId).email(actorEmail).build()));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        lenient().when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee()));
        lenient().when(penalisationPolicyRepository.findById(policyId))
                .thenReturn(Optional.of(PenalisationPolicy.builder().id(policyId).name("Standard Policy").build()));
        lenient().when(allocationRepository.save(any())).thenAnswer(inv -> {
            PenalizationPolicyAllocation a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            return a;
        });
    }

    private Employee employee() {
        return Employee.builder().userId(employeeId).employeeCode("NF-1").fullName("Jane Doe")
                .user(User.builder().id(employeeId).email("jane@test.com").active(true).build())
                .build();
    }

    // ── searchEmployees() ──────────────────────────────────────────────────────────

    @Test
    void searchEmployees_hydratesPageAndManagersInOneBulkCallEach_notPerEmployee() {
        Employee idOnlyProxy = Employee.builder().userId(employeeId).employeeCode("NF-1").fullName("Jane Doe")
                .user(User.builder().id(employeeId).build()).build();
        org.springframework.data.domain.Page<Employee> idPage =
                new org.springframework.data.domain.PageImpl<>(List.of(idOnlyProxy));
        when(employeeRepository.findAll(org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<Employee>>any(),
                any(org.springframework.data.domain.Pageable.class))).thenReturn(idPage);

        com.nforce.onehr.dto.penalization.EmployeeAllocationProjection hydrated =
                new com.nforce.onehr.dto.penalization.EmployeeAllocationProjection(
                        employeeId, "NF-1", "Jane Doe", "jane@test.com", true, "Senior Engineer",
                        null, null, null, null, null, null, null);
        when(employeeRepository.findAllocationProjectionsByIds(List.of(employeeId))).thenReturn(List.of(hydrated));

        when(resolutionService.resolveCurrentPolicyIdsByEmployee(today)).thenReturn(java.util.Map.of(employeeId, policyId));
        when(allocationRepository.findByEmployeeUserIdIn(List.of(employeeId))).thenReturn(List.of());
        com.nforce.onehr.dto.EmployeeResponse.ManagerRef manager = com.nforce.onehr.dto.EmployeeResponse.ManagerRef.builder()
                .userId(UUID.randomUUID().toString()).fullName("Sam Manager").email("sam@test.com").build();
        when(employeeService.findCurrentManagersBulk(List.of(employeeId))).thenReturn(java.util.Map.of(employeeId, manager));

        var response = service.searchEmployees(null, null, null, null, null, 0, 25, false);

        assertEquals(1, response.getContent().size());
        var row = response.getContent().get(0);
        assertEquals("Senior Engineer", row.getDesignationTitle());
        assertEquals("Sam Manager", row.getReportingManagerName());
        // Exactly one bulk hydration call and one bulk manager lookup, regardless of page size —
        // never one query per employee.
        verify(employeeRepository, times(1)).findAllocationProjectionsByIds(any());
        verify(employeeService, times(1)).findCurrentManagersBulk(any());
    }

    // ── allocate() ──────────────────────────────────────────────────────────────

    @Test
    void allocate_noExistingRows_succeeds() {
        when(allocationRepository.findOverlapping(employeeId, today, null, null)).thenReturn(List.of());

        CreateAllocationRequest req = new CreateAllocationRequest();
        req.setEmployeeUserId(employeeId);
        req.setPenalisationPolicyId(policyId);
        req.setEffectiveFrom(today);

        AllocationDto result = service.allocate(req, actorEmail);

        assertEquals(policyId, result.getPenalisationPolicyId());
        assertEquals("CURRENT", result.getStatus());
        verify(auditService).log(eq(actorId), eq("PENALIZATION_POLICY_ALLOCATION_ASSIGNED"), eq(employeeId), any(), any());
        verify(notificationService).send(eq(employeeId), eq("PENALIZATION_POLICY_CHANGED"), any(), any(), any());
    }

    @Test
    void allocate_overlappingExistingRow_isRejectedNotSilentlyTruncated() {
        PenalizationPolicyAllocation existing = PenalizationPolicyAllocation.builder()
                .id(UUID.randomUUID()).employeeUserId(employeeId).penalisationPolicyId(UUID.randomUUID())
                .effectiveFrom(today.minusDays(30)).effectiveTo(null).build();
        when(allocationRepository.findOverlapping(employeeId, today, null, null)).thenReturn(List.of(existing));
        when(penalisationPolicyRepository.findById(existing.getPenalisationPolicyId()))
                .thenReturn(Optional.of(PenalisationPolicy.builder().id(existing.getPenalisationPolicyId()).name("Old Policy").build()));

        CreateAllocationRequest req = new CreateAllocationRequest();
        req.setEmployeeUserId(employeeId);
        req.setPenalisationPolicyId(policyId);
        req.setEffectiveFrom(today);

        assertThrows(IllegalStateException.class, () -> service.allocate(req, actorEmail));
        // Rejected, not resolved by mutating the conflicting row.
        verify(allocationRepository, never()).delete(any());
        verify(allocationRepository, never()).save(existing);
    }

    @Test
    void allocate_effectiveToBeforeEffectiveFrom_rejected() {
        CreateAllocationRequest req = new CreateAllocationRequest();
        req.setEmployeeUserId(employeeId);
        req.setPenalisationPolicyId(policyId);
        req.setEffectiveFrom(today);
        req.setEffectiveTo(today.minusDays(1));

        assertThrows(IllegalArgumentException.class, () -> service.allocate(req, actorEmail));
    }

    @Test
    void allocate_unknownEmployee_throws() {
        UUID unknownId = UUID.randomUUID();
        when(employeeRepository.findById(unknownId)).thenReturn(Optional.empty());
        CreateAllocationRequest req = new CreateAllocationRequest();
        req.setEmployeeUserId(unknownId);
        req.setPenalisationPolicyId(policyId);
        req.setEffectiveFrom(today);

        assertThrows(java.util.NoSuchElementException.class, () -> service.allocate(req, actorEmail));
    }

    // ── update() ────────────────────────────────────────────────────────────────

    @Test
    void update_historicalAllocation_cannotBeEdited() {
        UUID allocationId = UUID.randomUUID();
        PenalizationPolicyAllocation historical = PenalizationPolicyAllocation.builder()
                .id(allocationId).employeeUserId(employeeId).penalisationPolicyId(policyId)
                .effectiveFrom(today.minusDays(60)).effectiveTo(today.minusDays(30)).build();
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.of(historical));

        UpdateAllocationRequest req = new UpdateAllocationRequest();
        req.setPenalisationPolicyId(policyId);
        req.setEffectiveFrom(today.minusDays(60));

        assertThrows(IllegalStateException.class, () -> service.update(allocationId, req, actorEmail));
        verify(allocationRepository, never()).save(any());
    }

    @Test
    void update_currentAllocation_excludesItselfFromOverlapCheck() {
        UUID allocationId = UUID.randomUUID();
        PenalizationPolicyAllocation current = PenalizationPolicyAllocation.builder()
                .id(allocationId).employeeUserId(employeeId).penalisationPolicyId(policyId)
                .effectiveFrom(today).effectiveTo(null).build();
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.of(current));
        when(allocationRepository.findOverlapping(employeeId, today, null, allocationId)).thenReturn(List.of());

        UpdateAllocationRequest req = new UpdateAllocationRequest();
        req.setPenalisationPolicyId(policyId);
        req.setEffectiveFrom(today);

        AllocationDto result = service.update(allocationId, req, actorEmail);
        assertEquals("CURRENT", result.getStatus());
        verify(allocationRepository).findOverlapping(employeeId, today, null, allocationId);
    }

    @Test
    void update_noOpResubmit_skipsNotificationAndSaveButStillChecksOverlap() {
        UUID allocationId = UUID.randomUUID();
        PenalizationPolicyAllocation current = PenalizationPolicyAllocation.builder()
                .id(allocationId).employeeUserId(employeeId).penalisationPolicyId(policyId)
                .effectiveFrom(today).effectiveTo(null).build();
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.of(current));
        when(allocationRepository.findOverlapping(employeeId, today, null, allocationId)).thenReturn(List.of());

        UpdateAllocationRequest req = new UpdateAllocationRequest();
        req.setPenalisationPolicyId(policyId);
        req.setEffectiveFrom(today);

        service.update(allocationId, req, actorEmail);

        verify(allocationRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
        verify(notificationService, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void update_genuineChange_savesAndNotifiesExactlyOnce() {
        UUID allocationId = UUID.randomUUID();
        UUID newPolicyId = UUID.randomUUID();
        PenalisationPolicy newPolicy = PenalisationPolicy.builder().id(newPolicyId).name("New Policy").build();
        when(penalisationPolicyRepository.findById(newPolicyId)).thenReturn(Optional.of(newPolicy));
        PenalizationPolicyAllocation current = PenalizationPolicyAllocation.builder()
                .id(allocationId).employeeUserId(employeeId).penalisationPolicyId(policyId)
                .effectiveFrom(today).effectiveTo(null).build();
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.of(current));
        when(allocationRepository.findOverlapping(employeeId, today, null, allocationId)).thenReturn(List.of());

        UpdateAllocationRequest req = new UpdateAllocationRequest();
        req.setPenalisationPolicyId(newPolicyId);
        req.setEffectiveFrom(today);

        service.update(allocationId, req, actorEmail);

        verify(allocationRepository).save(any());
        verify(notificationService, times(1)).send(eq(employeeId), eq("PENALIZATION_POLICY_CHANGED"), any(), any(), any());
    }

    // ── remove() ────────────────────────────────────────────────────────────────

    @Test
    void remove_historicalAllocation_cannotBeRemoved() {
        UUID allocationId = UUID.randomUUID();
        PenalizationPolicyAllocation historical = PenalizationPolicyAllocation.builder()
                .id(allocationId).employeeUserId(employeeId).penalisationPolicyId(policyId)
                .effectiveFrom(today.minusDays(60)).effectiveTo(today.minusDays(30)).build();
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.of(historical));

        assertThrows(IllegalStateException.class, () -> service.remove(allocationId, actorEmail));
        verify(allocationRepository, never()).delete(any());
    }

    @Test
    void remove_currentAllocationThatStartedToday_hasNoCompletedDayToPreserve_isDeleted() {
        UUID allocationId = UUID.randomUUID();
        PenalizationPolicyAllocation current = PenalizationPolicyAllocation.builder()
                .id(allocationId).employeeUserId(employeeId).penalisationPolicyId(policyId)
                .effectiveFrom(today).effectiveTo(null).build();
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.of(current));

        service.remove(allocationId, actorEmail);

        verify(allocationRepository).delete(current);
        verify(auditService).log(eq(actorId), eq("PENALIZATION_POLICY_ALLOCATION_REMOVED"), eq(employeeId), any(), isNull());
        verify(notificationService).send(eq(employeeId), eq("PENALIZATION_POLICY_CHANGED"), any(), any(), any());
    }

    @Test
    void remove_currentAllocationStartedInThePast_truncatesToYesterday_neverDeleted() {
        // Section 19's exact scenario: Policy A 01-Aug -> 31-Dec, removed on 25-Aug. The row must
        // survive as a closed HISTORICAL record (01-Aug -> 24-Aug), not be physically deleted —
        // a hard delete here would erase the only record of what actually governed this
        // employee's attendance evaluation for that window.
        UUID allocationId = UUID.randomUUID();
        PenalizationPolicyAllocation current = PenalizationPolicyAllocation.builder()
                .id(allocationId).employeeUserId(employeeId).penalisationPolicyId(policyId)
                .effectiveFrom(today.minusDays(24)).effectiveTo(today.plusDays(128)).build();
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.of(current));

        service.remove(allocationId, actorEmail);

        verify(allocationRepository, never()).delete(any());
        verify(allocationRepository).save(current);
        assertEquals(today.minusDays(1), current.getEffectiveTo());
        assertEquals(actorId, current.getUpdatedBy());
        verify(auditService).log(eq(actorId), eq("PENALIZATION_POLICY_ALLOCATION_REMOVED"), eq(employeeId), any(), any());
        verify(notificationService).send(eq(employeeId), eq("PENALIZATION_POLICY_CHANGED"), any(), any(), any());

        // And the truncated row must genuinely fall out of "current" as of today — a re-derived
        // status on the very same object now reports HISTORICAL, matching what
        // PenalizationPolicyResolutionService would see on its next lookup.
        assertTrue(current.getEffectiveTo().isBefore(today));
    }

    // ── bulkAllocate() / bulkRemove() ─────────────────────────────────────────────

    @Test
    void bulkAllocate_oneEmployeeMissing_othersStillSucceed() {
        UUID goodEmployeeId = employeeId;
        UUID missingEmployeeId = UUID.randomUUID();
        when(employeeRepository.findById(missingEmployeeId)).thenReturn(Optional.empty());
        when(allocationRepository.findOverlapping(eq(goodEmployeeId), any(), any(), isNull())).thenReturn(List.of());

        BulkAllocationRequest req = new BulkAllocationRequest();
        req.setEmployeeUserIds(List.of(goodEmployeeId, missingEmployeeId));
        req.setPenalisationPolicyId(policyId);
        req.setEffectiveFrom(today);

        AssignmentBulkResultResponse result = service.bulkAllocate(req, actorEmail);

        assertEquals(List.of(goodEmployeeId), result.getSucceededIds());
        assertEquals(1, result.getFailed().size());
        assertEquals(missingEmployeeId, result.getFailed().get(0).getEmployeeUserId());
        verify(auditService).log(eq(actorId), eq("PENALIZATION_POLICY_ALLOCATION_BULK_ASSIGNED"), eq(policyId), isNull(), any());
    }

    @Test
    void bulkRemove_employeeWithNoActiveAllocation_reportedAsFailure() {
        when(allocationRepository.findEffectiveAt(employeeId, today)).thenReturn(List.of());

        BulkRemoveAllocationRequest req = new BulkRemoveAllocationRequest();
        req.setEmployeeUserIds(List.of(employeeId));

        AssignmentBulkResultResponse result = service.bulkRemove(req, actorEmail);

        assertTrue(result.getSucceededIds().isEmpty());
        assertEquals(1, result.getFailed().size());
        assertEquals("No active allocation to remove", result.getFailed().get(0).getReason());
    }

    @Test
    void bulkRemove_employeeWithActiveAllocation_removesIt() {
        PenalizationPolicyAllocation current = PenalizationPolicyAllocation.builder()
                .id(UUID.randomUUID()).employeeUserId(employeeId).penalisationPolicyId(policyId)
                .effectiveFrom(today).effectiveTo(null).build();
        when(allocationRepository.findEffectiveAt(employeeId, today)).thenReturn(List.of(current));

        BulkRemoveAllocationRequest req = new BulkRemoveAllocationRequest();
        req.setEmployeeUserIds(List.of(employeeId));

        AssignmentBulkResultResponse result = service.bulkRemove(req, actorEmail);

        assertEquals(List.of(employeeId), result.getSucceededIds());
        verify(allocationRepository).delete(current);
    }

    @Test
    void bulkRemove_allocationStartedInThePast_truncatesRatherThanDeletes() {
        PenalizationPolicyAllocation current = PenalizationPolicyAllocation.builder()
                .id(UUID.randomUUID()).employeeUserId(employeeId).penalisationPolicyId(policyId)
                .effectiveFrom(today.minusDays(10)).effectiveTo(null).build();
        when(allocationRepository.findEffectiveAt(employeeId, today)).thenReturn(List.of(current));

        BulkRemoveAllocationRequest req = new BulkRemoveAllocationRequest();
        req.setEmployeeUserIds(List.of(employeeId));

        AssignmentBulkResultResponse result = service.bulkRemove(req, actorEmail);

        assertEquals(List.of(employeeId), result.getSucceededIds());
        verify(allocationRepository, never()).delete(any());
        verify(allocationRepository).save(current);
        assertEquals(today.minusDays(1), current.getEffectiveTo());
    }
}
