package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.assignments.AssignmentBulkResultResponse;
import com.nforce.onehr.dto.penalization.BulkAllocationRequest;
import com.nforce.onehr.dto.assignments.EmployeeAssignmentRow;
import com.nforce.onehr.dto.assignments.ImportResultResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeShiftAssignment;
import com.nforce.onehr.entity.PenalisationPolicy;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.entity.WeeklyOffPolicy;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.EmployeeShiftAssignmentRepository;
import com.nforce.onehr.repository.PenalisationPolicyRepository;
import com.nforce.onehr.repository.ShiftRepository;
import com.nforce.onehr.repository.WeeklyOffPolicyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ONEHR-108 bulk-update scoping — every write re-verifies the target is still a *current*
 * direct report at call time (mirrors LeaveService's approve/reject re-verify pattern), and
 * one employee's failure never blocks another's in the same batch.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeAssignmentServiceTest {

    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository managerHistoryRepository;
    @Mock private ShiftRepository shiftRepository;
    @Mock private EmployeeShiftAssignmentRepository employeeShiftAssignmentRepository;
    @Mock private WeeklyOffPolicyRepository weeklyOffPolicyRepository;
    @Mock private PenalisationPolicyRepository penalisationPolicyRepository;
    @Mock private AuditService auditService;
    @Mock private PenalizationPolicyAllocationService penalizationPolicyAllocationService;
    @Mock private PenalizationPolicyResolutionService penalizationPolicyResolutionService;
    @Mock private AttendanceProperties attendanceProperties;
    @Mock private ShiftVersionResolver shiftVersionResolver;
    @Mock private EmployeeShiftAssignmentResolver employeeShiftAssignmentResolver;
    @Mock private com.nforce.onehr.repository.EmployeeWeeklyOffPolicyAssignmentRepository employeeWeeklyOffPolicyAssignmentRepository;
    @Mock private EmployeeWeeklyOffPolicyAssignmentResolver employeeWeeklyOffPolicyAssignmentResolver;

    @InjectMocks private EmployeeAssignmentService service;

    private final UUID managerId = UUID.randomUUID();
    private final UUID directReportId = UUID.randomUUID();
    private final UUID strangerId = UUID.randomUUID();
    private final String managerEmail = "manager@test.com";

    @BeforeEach
    void setUp() {
        Employee manager = Employee.builder().userId(managerId).fullName("Manager").build();
        // lenient: the "policy not found" test throws before either of these is reached.
        lenient().when(employeeRepository.findByUser_Email(managerEmail)).thenReturn(Optional.of(manager));
        lenient().when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of(directReportId));
        // The org-wide business-day clock every Effective From validation/replacement decision
        // reads from (see AttendanceProperties.zone's own Javadoc) — lenient since not every test
        // exercises a code path that reads it.
        lenient().when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
    }

    @AfterEach
    void tearDown() {
        // Every ALL-scope test below sets this explicitly — cleared so no authentication ever
        // leaks across tests sharing this thread (JUnit/Surefire reuses threads across methods).
        SecurityContextHolder.clearContext();
    }

    /** Grants the CURRENT thread's SecurityContext a single ALL-scope authority for this module+action. */
    private void grantCompanyWideScope(String action) {
        Authentication authentication = mock(Authentication.class);
        doReturn(List.of(new SimpleGrantedAuthority("employee_assignments:" + action + ":ALL")))
                .when(authentication).getAuthorities();
        SecurityContext context = mock(SecurityContext.class);
        when(context.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(context);
    }

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private final LocalDate tomorrow = today.plusDays(1);

    @Test
    void bulkUpdateShift_succeeds_forCurrentDirectReport() {
        UUID shiftId = UUID.randomUUID();
        Shift shift = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(employeeRepository.findById(directReportId)).thenReturn(Optional.of(Employee.builder().userId(directReportId).build()));

        AssignmentBulkResultResponse result = service.bulkUpdateShift(managerEmail, List.of(directReportId), shiftId, tomorrow);

        assertEquals(1, result.getSucceededIds().size());
        assertTrue(result.getFailed().isEmpty());
        // Future-effective (ONEHR-336 follow-up): a new EmployeeShiftAssignment row is written —
        // Employee.shift_id (a non-authoritative display cache) is never touched by this path.
        ArgumentCaptor<EmployeeShiftAssignment> captor = ArgumentCaptor.forClass(EmployeeShiftAssignment.class);
        verify(employeeShiftAssignmentRepository).save(captor.capture());
        assertEquals(directReportId, captor.getValue().getEmployeeUserId());
        assertEquals(shift, captor.getValue().getShift());
        assertEquals(tomorrow, captor.getValue().getEffectiveFrom());
        verify(employeeRepository, never()).save(any());
    }

    // ── Pending-assignment replacement semantics (code-review corrective pass) ───────────────────
    // At most one PENDING (not-yet-effective) assignment is ever expected to exist for an
    // employee — assignShift must clear whichever one already exists (regardless of whether its
    // own date is earlier or later than the new one), and must also replace a row that exactly
    // collides with the new assignment's own date, while never touching an assignment that is
    // already CURRENTLY effective (governs an earlier date) unless its date exactly matches the
    // new one.

    private Shift newShift(String name) {
        UUID id = UUID.randomUUID();
        return Shift.builder().id(id).name(name).active(true).build();
    }

    private void stubShiftAssignable(Shift shift) {
        when(shiftRepository.findById(shift.getId())).thenReturn(Optional.of(shift));
        // No joiningDate set (null) — passes the D2 floor check unconditionally for these tests,
        // which are about pending/current-assignment replacement semantics, not D2 itself (see
        // EmployeeAssignmentServiceTest's own D2-specific tests below).
        when(employeeRepository.findById(directReportId)).thenReturn(Optional.of(Employee.builder().userId(directReportId).build()));
    }

    @Test
    void assignShift_clearsAnEarlierPendingAssignment_whenSchedulingALaterOne() {
        Shift shift = newShift("Night Shift");
        stubShiftAssignable(shift);
        LocalDate earlierPending = today.plusDays(2);
        LocalDate laterNew = today.plusDays(6);
        EmployeeShiftAssignment existingPending = EmployeeShiftAssignment.builder()
                .id(UUID.randomUUID()).employeeUserId(directReportId).effectiveFrom(earlierPending).build();
        when(employeeShiftAssignmentRepository
                .findByEmployeeUserIdInAndEffectiveFromGreaterThanOrderByEmployeeUserIdAscEffectiveFromAsc(List.of(directReportId), today))
                .thenReturn(List.of(existingPending));

        service.bulkUpdateShift(managerEmail, List.of(directReportId), shift.getId(), laterNew);

        verify(employeeShiftAssignmentRepository).delete(existingPending);
        verify(employeeShiftAssignmentRepository).save(argThat(a -> laterNew.equals(a.getEffectiveFrom())));
    }

    @Test
    void assignShift_clearsALaterPendingAssignment_whenSchedulingAnEarlierOne() {
        Shift shift = newShift("Night Shift");
        stubShiftAssignable(shift);
        LocalDate laterPending = today.plusDays(6);
        LocalDate earlierNew = today.plusDays(2);
        EmployeeShiftAssignment existingPending = EmployeeShiftAssignment.builder()
                .id(UUID.randomUUID()).employeeUserId(directReportId).effectiveFrom(laterPending).build();
        when(employeeShiftAssignmentRepository
                .findByEmployeeUserIdInAndEffectiveFromGreaterThanOrderByEmployeeUserIdAscEffectiveFromAsc(List.of(directReportId), today))
                .thenReturn(List.of(existingPending));

        service.bulkUpdateShift(managerEmail, List.of(directReportId), shift.getId(), earlierNew);

        verify(employeeShiftAssignmentRepository).delete(existingPending);
        verify(employeeShiftAssignmentRepository).save(argThat(a -> earlierNew.equals(a.getEffectiveFrom())));
    }

    @Test
    void assignShift_replacesAPendingAssignment_whenRescheduledToTheExactSameDate() {
        Shift shift = newShift("Night Shift");
        stubShiftAssignable(shift);
        LocalDate pendingDate = today.plusDays(6);
        EmployeeShiftAssignment existingPending = EmployeeShiftAssignment.builder()
                .id(UUID.randomUUID()).employeeUserId(directReportId).effectiveFrom(pendingDate).build();
        when(employeeShiftAssignmentRepository
                .findByEmployeeUserIdInAndEffectiveFromGreaterThanOrderByEmployeeUserIdAscEffectiveFromAsc(List.of(directReportId), today))
                .thenReturn(List.of(existingPending));

        service.bulkUpdateShift(managerEmail, List.of(directReportId), shift.getId(), pendingDate);

        // Exactly one row deleted (the stale pending one) — never a second delete for the same
        // row via the current-assignment check too (it isn't the current assignment).
        verify(employeeShiftAssignmentRepository, times(1)).delete(any());
        verify(employeeShiftAssignmentRepository).delete(existingPending);
        verify(employeeShiftAssignmentRepository).save(argThat(a -> pendingDate.equals(a.getEffectiveFrom())));
    }

    @Test
    void assignShift_leavesTheCurrentlyActiveAssignmentUntouched_whenSchedulingAFutureOne() {
        Shift currentShift = newShift("Day Shift");
        Shift scheduledShift = newShift("Night Shift");
        stubShiftAssignable(scheduledShift);
        LocalDate activeSince = today.minusDays(30);
        EmployeeShiftAssignment activeAssignment = EmployeeShiftAssignment.builder()
                .id(UUID.randomUUID()).employeeUserId(directReportId).shift(currentShift).effectiveFrom(activeSince).build();
        when(employeeShiftAssignmentRepository
                .findByEmployeeUserIdInAndEffectiveFromLessThanEqualOrderByEmployeeUserIdAscEffectiveFromDesc(List.of(directReportId), today))
                .thenReturn(List.of(activeAssignment));

        service.bulkUpdateShift(managerEmail, List.of(directReportId), scheduledShift.getId(), tomorrow);

        verify(employeeShiftAssignmentRepository, never()).delete(any());
        verify(employeeShiftAssignmentRepository).save(argThat(a -> tomorrow.equals(a.getEffectiveFrom())));
    }

    @Test
    void assignShift_noExistingAssignmentAtAll_justInsertsTheNewOne() {
        Shift shift = newShift("Night Shift");
        stubShiftAssignable(shift);
        // No stubbing needed for the batch pending/current lookups — both default to an empty
        // list (no assignment at all for this employee), exactly what this test exercises.

        service.bulkUpdateShift(managerEmail, List.of(directReportId), shift.getId(), tomorrow);

        verify(employeeShiftAssignmentRepository, never()).delete(any());
        verify(employeeShiftAssignmentRepository).save(argThat(a -> tomorrow.equals(a.getEffectiveFrom())));
    }

    /**
     * Code-review fix: assignShift used to re-query pending/current per employee (up to 2 extra
     * SELECTs each) — a 200-employee bulk reassignment turned into hundreds of sequential round
     * trips. bulkUpdateShift now batch-resolves both ONCE for the whole request, the same way
     * listTeamAssignments already does — never a per-employee SELECT.
     */
    @Test
    void bulkUpdateShift_resolvesPendingAndCurrentAssignments_inTwoBatchQueries_neverPerEmployee() {
        UUID emp2 = UUID.randomUUID();
        UUID emp3 = UUID.randomUUID();
        List<UUID> employeeIds = List.of(directReportId, emp2, emp3);
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(employeeIds);
        Shift shift = newShift("Night Shift");
        when(shiftRepository.findById(shift.getId())).thenReturn(Optional.of(shift));
        when(employeeRepository.findById(any())).thenReturn(Optional.of(Employee.builder().build()));

        service.bulkUpdateShift(managerEmail, employeeIds, shift.getId(), tomorrow);

        verify(employeeShiftAssignmentRepository, times(1))
                .findByEmployeeUserIdInAndEffectiveFromGreaterThanOrderByEmployeeUserIdAscEffectiveFromAsc(employeeIds, today);
        verify(employeeShiftAssignmentRepository, times(1))
                .findByEmployeeUserIdInAndEffectiveFromLessThanEqualOrderByEmployeeUserIdAscEffectiveFromDesc(employeeIds, today);
        verify(employeeShiftAssignmentRepository, never())
                .findFirstByEmployeeUserIdAndEffectiveFromGreaterThanOrderByEffectiveFromAsc(any(), any());
        verifyNoInteractions(employeeShiftAssignmentResolver);
        verify(employeeShiftAssignmentRepository, times(3)).save(any());
    }

    /**
     * Business rule: today is a VALID Effective From choice — the assignment is effective today
     * itself, never silently pushed to tomorrow or rejected as "too soon."
     */
    @Test
    void bulkUpdateShift_acceptsToday_effectiveTodayItself() {
        UUID shiftId = UUID.randomUUID();
        Shift shift = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(employeeRepository.findById(directReportId)).thenReturn(Optional.of(Employee.builder().userId(directReportId).build()));
        LocalDate today = LocalDate.now();

        AssignmentBulkResultResponse result = assertDoesNotThrow(() ->
                service.bulkUpdateShift(managerEmail, List.of(directReportId), shiftId, today));

        assertEquals(1, result.getSucceededIds().size());
        ArgumentCaptor<EmployeeShiftAssignment> captor = ArgumentCaptor.forClass(EmployeeShiftAssignment.class);
        verify(employeeShiftAssignmentRepository).save(captor.capture());
        assertEquals(today, captor.getValue().getEffectiveFrom());
    }

    /**
     * Code-review corrective pass: the "cannot be in the past" check must read the org-wide
     * business-day clock (AttendanceProperties.zone) rather than the JVM default zone — this
     * fails if that dependency is ever removed/bypassed again.
     */
    @Test
    void bulkUpdateShift_effectiveFromValidation_readsConfiguredBusinessZone_notJvmDefault() {
        UUID shiftId = UUID.randomUUID();
        Shift shift = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(employeeRepository.findById(directReportId)).thenReturn(Optional.of(Employee.builder().userId(directReportId).build()));

        service.bulkUpdateShift(managerEmail, List.of(directReportId), shiftId, tomorrow);

        verify(attendanceProperties, atLeastOnce()).getZone();
    }

    @Test
    void bulkUpdateShift_rejectsPastDate() {
        UUID shiftId = UUID.randomUUID();
        when(shiftRepository.findById(shiftId))
                .thenReturn(Optional.of(Shift.builder().id(shiftId).name("Regular Shift").active(true).build()));

        assertThrows(IllegalArgumentException.class,
                () -> service.bulkUpdateShift(managerEmail, List.of(directReportId), shiftId, LocalDate.now().minusDays(1)));
        verifyNoInteractions(employeeShiftAssignmentRepository);
    }

    @Test
    void bulkUpdateShift_rejectsNullEffectiveFrom() {
        UUID shiftId = UUID.randomUUID();
        when(shiftRepository.findById(shiftId))
                .thenReturn(Optional.of(Shift.builder().id(shiftId).name("Regular Shift").active(true).build()));

        assertThrows(IllegalArgumentException.class,
                () -> service.bulkUpdateShift(managerEmail, List.of(directReportId), shiftId, null));
        verifyNoInteractions(employeeShiftAssignmentRepository);
    }

    @Test
    void bulkUpdateShift_fails_forEmployeeNotACurrentDirectReport() {
        UUID shiftId = UUID.randomUUID();
        when(shiftRepository.findById(shiftId))
                .thenReturn(Optional.of(Shift.builder().id(shiftId).name("Regular Shift").active(true).build()));

        AssignmentBulkResultResponse result = service.bulkUpdateShift(managerEmail, List.of(strangerId), shiftId, tomorrow);

        assertTrue(result.getSucceededIds().isEmpty());
        assertEquals(1, result.getFailed().size());
        assertEquals(strangerId, result.getFailed().get(0).getEmployeeUserId());
        verify(employeeRepository, never()).findById(strangerId);
        verifyNoInteractions(employeeShiftAssignmentRepository);
    }

    @Test
    void bulkUpdateShift_isPartial_whenOneOfTwoEmployeesIsNotADirectReport() {
        UUID shiftId = UUID.randomUUID();
        Shift shift = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(employeeRepository.findById(directReportId)).thenReturn(Optional.of(Employee.builder().userId(directReportId).build()));

        AssignmentBulkResultResponse result =
                service.bulkUpdateShift(managerEmail, List.of(directReportId, strangerId), shiftId, tomorrow);

        assertEquals(1, result.getSucceededIds().size());
        assertEquals(directReportId, result.getSucceededIds().get(0));
        assertEquals(1, result.getFailed().size());
        assertEquals(strangerId, result.getFailed().get(0).getEmployeeUserId());
    }

    @Test
    void bulkUpdateShift_throws_whenShiftDoesNotExist() {
        UUID unknownShiftId = UUID.randomUUID();
        when(shiftRepository.findById(unknownShiftId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.bulkUpdateShift(managerEmail, List.of(directReportId), unknownShiftId, tomorrow));
        verify(employeeRepository, never()).findById(any());
    }

    @Test
    void bulkUpdateShift_throws_whenShiftIsInactive() {
        UUID shiftId = UUID.randomUUID();
        when(shiftRepository.findById(shiftId))
                .thenReturn(Optional.of(Shift.builder().id(shiftId).name("Retired Shift").active(false).build()));

        assertThrows(IllegalArgumentException.class,
                () -> service.bulkUpdateShift(managerEmail, List.of(directReportId), shiftId, tomorrow));
        verifyNoInteractions(employeeShiftAssignmentRepository);
    }

    // ── Phase 3: Weekly Off Policy bulk-assign is now effective-dated, mirroring bulkUpdateShift's
    //    identical design (new EmployeeWeeklyOffPolicyAssignment row, never Employee.weeklyOffPolicy). ──

    private WeeklyOffPolicy newWeeklyOffPolicy(String name) {
        return WeeklyOffPolicy.builder().id(UUID.randomUUID()).name(name).offDays("SATURDAY,SUNDAY").build();
    }

    @Test
    void bulkUpdateWeeklyOff_succeeds_withExplicitEffectiveFrom() {
        WeeklyOffPolicy policy = newWeeklyOffPolicy("Standard");
        when(weeklyOffPolicyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
        when(employeeRepository.findById(directReportId)).thenReturn(Optional.of(Employee.builder().userId(directReportId).build()));

        AssignmentBulkResultResponse result = service.bulkUpdateWeeklyOff(managerEmail, List.of(directReportId), policy.getId(), tomorrow);

        assertEquals(1, result.getSucceededIds().size());
        assertTrue(result.getFailed().isEmpty());
        ArgumentCaptor<com.nforce.onehr.entity.EmployeeWeeklyOffPolicyAssignment> captor =
                ArgumentCaptor.forClass(com.nforce.onehr.entity.EmployeeWeeklyOffPolicyAssignment.class);
        verify(employeeWeeklyOffPolicyAssignmentRepository).save(captor.capture());
        assertEquals(directReportId, captor.getValue().getEmployeeUserId());
        assertEquals(policy, captor.getValue().getWeeklyOffPolicy());
        assertEquals(tomorrow, captor.getValue().getEffectiveFrom());
        verify(employeeRepository, never()).save(any());
    }

    @Test
    void bulkUpdateWeeklyOff_rejectsMissingEffectiveFrom() {
        WeeklyOffPolicy policy = newWeeklyOffPolicy("Standard");
        when(weeklyOffPolicyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));

        assertThrows(IllegalArgumentException.class,
                () -> service.bulkUpdateWeeklyOff(managerEmail, List.of(directReportId), policy.getId(), null));
        verifyNoInteractions(employeeWeeklyOffPolicyAssignmentRepository);
    }

    @Test
    void bulkUpdateWeeklyOff_rejectsPastEffectiveFrom() {
        WeeklyOffPolicy policy = newWeeklyOffPolicy("Standard");
        when(weeklyOffPolicyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));

        assertThrows(IllegalArgumentException.class,
                () -> service.bulkUpdateWeeklyOff(managerEmail, List.of(directReportId), policy.getId(), today.minusDays(1)));
        verifyNoInteractions(employeeWeeklyOffPolicyAssignmentRepository);
    }

    @Test
    void bulkUpdateWeeklyOff_rejectsEffectiveFromBeforeEmployeesJoiningDate() {
        WeeklyOffPolicy policy = newWeeklyOffPolicy("Standard");
        when(weeklyOffPolicyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
        LocalDate futureJoining = tomorrow.plusDays(10);
        when(employeeRepository.findById(directReportId))
                .thenReturn(Optional.of(Employee.builder().userId(directReportId).joiningDate(futureJoining).build()));

        AssignmentBulkResultResponse result =
                service.bulkUpdateWeeklyOff(managerEmail, List.of(directReportId), policy.getId(), tomorrow);

        assertTrue(result.getSucceededIds().isEmpty());
        assertEquals(1, result.getFailed().size());
        assertTrue(result.getFailed().get(0).getReason().contains("joining date"));
        verify(employeeWeeklyOffPolicyAssignmentRepository, never()).save(any());
    }

    @Test
    void bulkUpdateWeeklyOff_fails_forEmployeeNotACurrentDirectReport() {
        WeeklyOffPolicy policy = newWeeklyOffPolicy("Standard");
        when(weeklyOffPolicyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));

        AssignmentBulkResultResponse result = service.bulkUpdateWeeklyOff(managerEmail, List.of(strangerId), policy.getId(), tomorrow);

        assertTrue(result.getSucceededIds().isEmpty());
        assertEquals(1, result.getFailed().size());
        assertEquals("Not a current direct report", result.getFailed().get(0).getReason());
        verifyNoInteractions(employeeWeeklyOffPolicyAssignmentRepository);
    }

    @Test
    void bulkUpdateWeeklyOff_replacesAnyExistingPendingAssignment() {
        WeeklyOffPolicy policy = newWeeklyOffPolicy("Standard");
        when(weeklyOffPolicyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
        when(employeeRepository.findById(directReportId)).thenReturn(Optional.of(Employee.builder().userId(directReportId).build()));
        com.nforce.onehr.entity.EmployeeWeeklyOffPolicyAssignment existingPending =
                com.nforce.onehr.entity.EmployeeWeeklyOffPolicyAssignment.builder()
                        .id(UUID.randomUUID()).employeeUserId(directReportId).effectiveFrom(today.plusDays(5)).build();
        when(employeeWeeklyOffPolicyAssignmentRepository
                .findByEmployeeUserIdInAndEffectiveFromGreaterThanOrderByEmployeeUserIdAscEffectiveFromAsc(List.of(directReportId), today))
                .thenReturn(List.of(existingPending));

        service.bulkUpdateWeeklyOff(managerEmail, List.of(directReportId), policy.getId(), today.plusDays(10));

        verify(employeeWeeklyOffPolicyAssignmentRepository).delete(existingPending);
        verify(employeeWeeklyOffPolicyAssignmentRepository).save(argThat(a -> today.plusDays(10).equals(a.getEffectiveFrom())));
    }

    @Test
    void bulkUpdateWeeklyOff_succeeds_forNonDirectReport_whenActorHasCompanyWideAllScope() {
        grantCompanyWideScope("EDIT");
        WeeklyOffPolicy policy = newWeeklyOffPolicy("Standard");
        when(weeklyOffPolicyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
        when(employeeRepository.findAll()).thenReturn(List.of(Employee.builder().userId(strangerId).build()));
        when(employeeRepository.findById(strangerId)).thenReturn(Optional.of(Employee.builder().userId(strangerId).build()));

        AssignmentBulkResultResponse result = service.bulkUpdateWeeklyOff(managerEmail, List.of(strangerId), policy.getId(), tomorrow);

        assertEquals(1, result.getSucceededIds().size());
        assertTrue(result.getFailed().isEmpty());
        verifyNoInteractions(managerHistoryRepository);
    }

    // ── Section 26: Penalisation Policy bulk-assign routes through the Allocation service ────

    @Test
    void bulkUpdatePenalisationPolicy_routesThroughAllocationService_effectiveToday() {
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        UUID policyId = UUID.randomUUID();
        when(penalizationPolicyAllocationService.bulkAllocate(any(), eq(managerEmail))).thenReturn(
                AssignmentBulkResultResponse.builder().succeededIds(List.of(directReportId)).failed(List.of()).build());

        AssignmentBulkResultResponse result =
                service.bulkUpdatePenalisationPolicy(managerEmail, List.of(directReportId), policyId);

        assertEquals(List.of(directReportId), result.getSucceededIds());
        assertTrue(result.getFailed().isEmpty());
        ArgumentCaptor<BulkAllocationRequest> captor = ArgumentCaptor.forClass(BulkAllocationRequest.class);
        verify(penalizationPolicyAllocationService).bulkAllocate(captor.capture(), eq(managerEmail));
        assertEquals(List.of(directReportId), captor.getValue().getEmployeeUserIds());
        assertEquals(policyId, captor.getValue().getPenalisationPolicyId());
        assertEquals(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")), captor.getValue().getEffectiveFrom());
        assertNull(captor.getValue().getEffectiveTo(), "open-ended — this screen has no end-date picker");
        // No duplicate audit entry here — PenalizationPolicyAllocationService's own bulkAllocate
        // already logs one, with more per-employee detail than this call site could add.
        verifyNoInteractions(auditService);
    }

    @Test
    void bulkUpdatePenalisationPolicy_employeeNotACurrentDirectReport_reportedAsFailure_neverSentToAllocationService() {
        UUID policyId = UUID.randomUUID();

        AssignmentBulkResultResponse result =
                service.bulkUpdatePenalisationPolicy(managerEmail, List.of(strangerId), policyId);

        assertTrue(result.getSucceededIds().isEmpty());
        assertEquals(1, result.getFailed().size());
        assertEquals(strangerId, result.getFailed().get(0).getEmployeeUserId());
        verifyNoInteractions(penalizationPolicyAllocationService);
    }

    @Test
    void bulkUpdatePenalisationPolicy_mergesManagerScopeFailuresWithAllocationServiceFailures() {
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        UUID policyId = UUID.randomUUID();
        when(penalizationPolicyAllocationService.bulkAllocate(any(), eq(managerEmail))).thenReturn(
                AssignmentBulkResultResponse.builder().succeededIds(List.of()).failed(List.of(
                        AssignmentBulkResultResponse.FailureDto.builder().employeeUserId(directReportId)
                                .reason("This employee already has an allocation to \"Other Policy\" covering ...").build()
                )).build());

        AssignmentBulkResultResponse result =
                service.bulkUpdatePenalisationPolicy(managerEmail, List.of(directReportId, strangerId), policyId);

        assertTrue(result.getSucceededIds().isEmpty());
        assertEquals(2, result.getFailed().size());
        assertTrue(result.getFailed().stream().anyMatch(f -> f.getEmployeeUserId().equals(strangerId)
                && "Not a current direct report".equals(f.getReason())));
        assertTrue(result.getFailed().stream().anyMatch(f -> f.getEmployeeUserId().equals(directReportId)
                && f.getReason().contains("already has an allocation")));
    }

    // ── GAP-014: Team Assignments' policy column/filter must read the authoritative resolution,
    // not the legacy Employee.penalisationPolicy FK nothing writes to any more. ────────────────

    @Test
    void listTeamAssignments_displaysResolvedPolicy_notLegacyEmployeeFk() {
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        UUID resolvedPolicyId = UUID.randomUUID();
        Employee employee = Employee.builder().userId(directReportId).fullName("Report One")
                .user(User.builder().id(directReportId).build()).build();
        when(employeeRepository.findAllById(List.of(directReportId))).thenReturn(List.of(employee));
        when(penalizationPolicyResolutionService.resolveCurrentPolicyIdsByEmployee(any()))
                .thenReturn(Map.of(directReportId, resolvedPolicyId));
        when(penalisationPolicyRepository.findById(resolvedPolicyId))
                .thenReturn(Optional.of(PenalisationPolicy.builder().id(resolvedPolicyId).name("Strict Policy").build()));

        List<EmployeeAssignmentRow> rows =
                service.listTeamAssignments(managerEmail, null, null, null, null, null, null);

        assertEquals(1, rows.size());
        assertEquals(resolvedPolicyId, rows.get(0).getPenalisationPolicyId());
        assertEquals("Strict Policy", rows.get(0).getPenalisationPolicyName());
    }

    @Test
    void listTeamAssignments_filtersByResolvedPolicy_notLegacyEmployeeFk() {
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        UUID matchingPolicyId = UUID.randomUUID();
        UUID otherPolicyId = UUID.randomUUID();
        UUID secondReportId = UUID.randomUUID();
        Employee reportA = Employee.builder().userId(directReportId).fullName("Report A")
                .user(User.builder().id(directReportId).build()).build();
        Employee reportB = Employee.builder().userId(secondReportId).fullName("Report B")
                .user(User.builder().id(secondReportId).build()).build();
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId))
                .thenReturn(List.of(directReportId, secondReportId));
        when(employeeRepository.findAllById(List.of(directReportId, secondReportId))).thenReturn(List.of(reportA, reportB));
        when(penalizationPolicyResolutionService.resolveCurrentPolicyIdsByEmployee(any()))
                .thenReturn(Map.of(directReportId, matchingPolicyId, secondReportId, otherPolicyId));
        lenient().when(penalisationPolicyRepository.findById(matchingPolicyId))
                .thenReturn(Optional.of(PenalisationPolicy.builder().id(matchingPolicyId).name("Match").build()));

        List<EmployeeAssignmentRow> rows =
                service.listTeamAssignments(managerEmail, null, null, matchingPolicyId, null, null, null);

        assertEquals(1, rows.size());
        assertEquals(directReportId, rows.get(0).getEmployeeUserId());
    }

    // ── Filter/display source-of-truth consistency (code-review corrective pass) ────────────────
    // The shiftId filter must match the SAME authoritative source toRow's own display reads from
    // — never Employee.shift, a best-effort cache a reassignment via bulkUpdateShift/CSV import
    // never updates, which could otherwise disagree with what the row itself shows.

    @Test
    void listTeamAssignments_filtersByAuthoritativeCurrentShift_notStaleEmployeeShiftCache() {
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        Shift staleCachedShift = Shift.builder().id(UUID.randomUUID()).name("Old Shift (stale cache)").active(true).build();
        Shift authoritativeShift = Shift.builder().id(UUID.randomUUID()).name("Actual Current Shift").active(true).build();
        // Employee.shift still points at the OLD shift — a reassignment via bulkUpdateShift/CSV
        // import never updates this display-cache field (see EmployeeAssignmentService#assignShift
        // — it only ever writes an EmployeeShiftAssignment row).
        Employee employee = Employee.builder().userId(directReportId).fullName("Report One")
                .user(User.builder().id(directReportId).build()).shift(staleCachedShift).build();
        when(employeeRepository.findAllById(List.of(directReportId))).thenReturn(List.of(employee));
        when(penalizationPolicyResolutionService.resolveCurrentPolicyIdsByEmployee(any())).thenReturn(Map.of());
        when(employeeShiftAssignmentRepository
                .findByEmployeeUserIdInAndEffectiveFromLessThanEqualOrderByEmployeeUserIdAscEffectiveFromDesc(any(), any()))
                .thenReturn(List.of(EmployeeShiftAssignment.builder()
                        .employeeUserId(directReportId).shift(authoritativeShift).effectiveFrom(LocalDate.now().minusDays(5)).build()));
        when(shiftVersionResolver.resolveCurrent(authoritativeShift)).thenReturn(
                com.nforce.onehr.entity.ShiftVersion.builder().startTime(java.time.LocalTime.of(9, 0)).endTime(java.time.LocalTime.of(18, 0)).build());

        // Filtering by the AUTHORITATIVE (currently-resolved) shift matches, and is exactly what
        // the row itself displays...
        List<EmployeeAssignmentRow> matching =
                service.listTeamAssignments(managerEmail, authoritativeShift.getId(), null, null, null, null, null);
        assertEquals(1, matching.size());
        assertEquals("Actual Current Shift", matching.get(0).getShiftName());

        // ...but filtering by the STALE Employee.shift cache value does not, since it no longer
        // reflects what actually governs this employee (or what the row above displays).
        List<EmployeeAssignmentRow> stale =
                service.listTeamAssignments(managerEmail, staleCachedShift.getId(), null, null, null, null, null);
        assertTrue(stale.isEmpty(), "filter must not match the stale Employee.shift display cache");
    }

    @Test
    void listTeamAssignments_resolvesShiftAssignmentsInOneBatch_notPerEmployee() {
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        UUID secondReportId = UUID.randomUUID();
        UUID thirdReportId = UUID.randomUUID();
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId))
                .thenReturn(List.of(directReportId, secondReportId, thirdReportId));
        Employee reportA = Employee.builder().userId(directReportId).fullName("A")
                .user(User.builder().id(directReportId).build()).build();
        Employee reportB = Employee.builder().userId(secondReportId).fullName("B")
                .user(User.builder().id(secondReportId).build()).build();
        Employee reportC = Employee.builder().userId(thirdReportId).fullName("C")
                .user(User.builder().id(thirdReportId).build()).build();
        when(employeeRepository.findAllById(List.of(directReportId, secondReportId, thirdReportId)))
                .thenReturn(List.of(reportA, reportB, reportC));
        when(penalizationPolicyResolutionService.resolveCurrentPolicyIdsByEmployee(any())).thenReturn(Map.of());

        service.listTeamAssignments(managerEmail, null, null, null, null, null, null);

        // Exactly one batch query for "current" and one for "pending", regardless of team size —
        // never a per-employee resolver/pending lookup (the N+1 pattern this replaces).
        verify(employeeShiftAssignmentRepository, times(1))
                .findByEmployeeUserIdInAndEffectiveFromLessThanEqualOrderByEmployeeUserIdAscEffectiveFromDesc(any(), any());
        verify(employeeShiftAssignmentRepository, times(1))
                .findByEmployeeUserIdInAndEffectiveFromGreaterThanOrderByEmployeeUserIdAscEffectiveFromAsc(any(), any());
        verifyNoInteractions(employeeShiftAssignmentResolver);
        verify(employeeShiftAssignmentRepository, never())
                .findFirstByEmployeeUserIdAndEffectiveFromGreaterThanOrderByEffectiveFromAsc(any(), any());
    }

    // ── Effective-date display: "Active since" vs "Scheduled" ────────────────────────────────

    @Test
    void listTeamAssignments_showsActiveSince_forTheCurrentlyEffectiveAssignment() {
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        Employee employee = Employee.builder().userId(directReportId).fullName("Report One")
                .user(User.builder().id(directReportId).build()).build();
        when(employeeRepository.findAllById(List.of(directReportId))).thenReturn(List.of(employee));
        when(penalizationPolicyResolutionService.resolveCurrentPolicyIdsByEmployee(any())).thenReturn(Map.of());
        Shift shift = Shift.builder().id(UUID.randomUUID()).name("Night Shift").active(true).build();
        LocalDate effectiveSince = LocalDate.now().minusDays(10);
        when(employeeShiftAssignmentRepository
                .findByEmployeeUserIdInAndEffectiveFromLessThanEqualOrderByEmployeeUserIdAscEffectiveFromDesc(any(), any()))
                .thenReturn(List.of(EmployeeShiftAssignment.builder()
                        .employeeUserId(directReportId).shift(shift).effectiveFrom(effectiveSince).build()));
        when(shiftVersionResolver.resolveCurrent(shift)).thenReturn(
                com.nforce.onehr.entity.ShiftVersion.builder().startTime(java.time.LocalTime.of(9, 0)).endTime(java.time.LocalTime.of(18, 0)).build());

        List<EmployeeAssignmentRow> rows =
                service.listTeamAssignments(managerEmail, null, null, null, null, null, null);

        assertEquals(1, rows.size());
        assertEquals("Night Shift", rows.get(0).getShiftName());
        assertEquals(effectiveSince, rows.get(0).getShiftEffectiveSince());
        assertNull(rows.get(0).getPendingShiftName(), "no scheduled change — nothing pending");
    }

    @Test
    void listTeamAssignments_showsScheduled_forAFutureDatedAssignment_distinctFromTheCurrentOne() {
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        Employee employee = Employee.builder().userId(directReportId).fullName("Report One")
                .user(User.builder().id(directReportId).build()).build();
        when(employeeRepository.findAllById(List.of(directReportId))).thenReturn(List.of(employee));
        when(penalizationPolicyResolutionService.resolveCurrentPolicyIdsByEmployee(any())).thenReturn(Map.of());
        Shift currentShift = Shift.builder().id(UUID.randomUUID()).name("Day Shift").active(true).build();
        Shift scheduledShift = Shift.builder().id(UUID.randomUUID()).name("Night Shift").active(true).build();
        LocalDate today = LocalDate.now();
        when(employeeShiftAssignmentRepository
                .findByEmployeeUserIdInAndEffectiveFromLessThanEqualOrderByEmployeeUserIdAscEffectiveFromDesc(any(), any()))
                .thenReturn(List.of(EmployeeShiftAssignment.builder()
                        .employeeUserId(directReportId).shift(currentShift).effectiveFrom(today.minusDays(30)).build()));
        when(shiftVersionResolver.resolveCurrent(currentShift)).thenReturn(
                com.nforce.onehr.entity.ShiftVersion.builder().startTime(java.time.LocalTime.of(9, 0)).endTime(java.time.LocalTime.of(18, 0)).build());
        when(employeeShiftAssignmentRepository
                .findByEmployeeUserIdInAndEffectiveFromGreaterThanOrderByEmployeeUserIdAscEffectiveFromAsc(any(), any()))
                .thenReturn(List.of(EmployeeShiftAssignment.builder()
                        .employeeUserId(directReportId).shift(scheduledShift).effectiveFrom(today.plusDays(3)).build()));

        List<EmployeeAssignmentRow> rows =
                service.listTeamAssignments(managerEmail, null, null, null, null, null, null);

        assertEquals(1, rows.size());
        // The CURRENT shift remains what's reported as active — the scheduled one does not take
        // over early, exactly as the business rule requires.
        assertEquals("Day Shift", rows.get(0).getShiftName());
        assertEquals(today.minusDays(30), rows.get(0).getShiftEffectiveSince());
        assertEquals("Night Shift", rows.get(0).getPendingShiftName());
        assertEquals(today.plusDays(3), rows.get(0).getPendingShiftEffectiveFrom());
    }

    // ── Authorization scope fix (F3/§4): HR_ADMIN/SUPER_ADMIN get the company-wide ALL scope
    // V225 actually grants, instead of being bugged down to direct-reports-only like Manager. ──

    @Test
    void bulkUpdateShift_succeeds_forNonDirectReport_whenActorHasCompanyWideAllScope() {
        grantCompanyWideScope("EDIT");
        UUID shiftId = UUID.randomUUID();
        Shift shift = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        // strangerId is NOT a current direct report of this manager — only reachable at all
        // because ALL-scope bypasses the direct-report filter entirely and sources authorized ids
        // from the whole company instead.
        when(employeeRepository.findAll()).thenReturn(List.of(Employee.builder().userId(strangerId).build()));
        when(employeeRepository.findById(strangerId)).thenReturn(Optional.of(Employee.builder().userId(strangerId).build()));

        AssignmentBulkResultResponse result = service.bulkUpdateShift(managerEmail, List.of(strangerId), shiftId, tomorrow);

        assertEquals(1, result.getSucceededIds().size());
        assertTrue(result.getFailed().isEmpty());
        verifyNoInteractions(managerHistoryRepository);
    }

    @Test
    void bulkUpdateShift_stillRestrictedToDirectReports_whenActorHasOnlyTeamScope() {
        // No grantCompanyWideScope call — the default (no authentication set) exercises the exact
        // same TEAM-only path every other test in this class already relies on.
        UUID shiftId = UUID.randomUUID();
        when(shiftRepository.findById(shiftId))
                .thenReturn(Optional.of(Shift.builder().id(shiftId).name("Regular Shift").active(true).build()));

        AssignmentBulkResultResponse result = service.bulkUpdateShift(managerEmail, List.of(strangerId), shiftId, tomorrow);

        assertTrue(result.getSucceededIds().isEmpty());
        assertEquals(1, result.getFailed().size());
        assertEquals("Not a current direct report", result.getFailed().get(0).getReason());
    }

    @Test
    void listTeamAssignments_includesCompanyWideEmployees_whenActorHasAllScope() {
        grantCompanyWideScope("VIEW");
        Employee anyEmployee = Employee.builder().userId(strangerId).fullName("Anyone")
                .user(User.builder().id(strangerId).build()).build();
        when(employeeRepository.findAll()).thenReturn(List.of(anyEmployee));
        when(employeeRepository.findAllById(List.of(strangerId))).thenReturn(List.of(anyEmployee));
        when(penalizationPolicyResolutionService.resolveCurrentPolicyIdsByEmployee(any())).thenReturn(Map.of());

        List<EmployeeAssignmentRow> rows =
                service.listTeamAssignments(managerEmail, null, null, null, null, null, null);

        assertEquals(1, rows.size());
        assertEquals(strangerId, rows.get(0).getEmployeeUserId());
        // Never falls back to the manager's own direct-report list once ALL scope is granted.
        verifyNoInteractions(managerHistoryRepository);
    }

    // ── D2: shift effective-date floor — cannot predate the employee's own joining date ──────

    @Test
    void bulkUpdateShift_rejectsEffectiveFromBeforeEmployeesJoiningDate() {
        UUID shiftId = UUID.randomUUID();
        Shift shift = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        LocalDate futureJoining = tomorrow.plusDays(10);
        when(employeeRepository.findById(directReportId))
                .thenReturn(Optional.of(Employee.builder().userId(directReportId).joiningDate(futureJoining).build()));

        AssignmentBulkResultResponse result =
                service.bulkUpdateShift(managerEmail, List.of(directReportId), shiftId, tomorrow);

        assertTrue(result.getSucceededIds().isEmpty());
        assertEquals(1, result.getFailed().size());
        assertTrue(result.getFailed().get(0).getReason().contains("joining date"));
        verify(employeeShiftAssignmentRepository, never()).save(any());
    }

    @Test
    void bulkUpdateShift_acceptsEffectiveFromOnOrAfterEmployeesJoiningDate() {
        UUID shiftId = UUID.randomUUID();
        Shift shift = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(employeeRepository.findById(directReportId))
                .thenReturn(Optional.of(Employee.builder().userId(directReportId).joiningDate(tomorrow).build()));

        AssignmentBulkResultResponse result =
                service.bulkUpdateShift(managerEmail, List.of(directReportId), shiftId, tomorrow);

        assertEquals(1, result.getSucceededIds().size());
        assertTrue(result.getFailed().isEmpty());
    }

    // ── Phase 3: listTeamAssignments' weekly-off column/filter must read the authoritative,
    //    effective-dated assignment — never the stale Employee.weeklyOffPolicy display cache. ──

    @Test
    void listTeamAssignments_resolvesWeeklyOffFromAuthoritativeAssignment_notStaleEmployeeCache() {
        when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        WeeklyOffPolicy staleCachedPolicy = WeeklyOffPolicy.builder().id(UUID.randomUUID()).name("Stale Cache Policy").build();
        WeeklyOffPolicy authoritativePolicy = WeeklyOffPolicy.builder().id(UUID.randomUUID()).name("Actual Current Policy").build();
        // Employee.weeklyOffPolicy still points at the OLD policy — a reassignment via
        // bulkUpdateWeeklyOff/CSV import never updates this display-cache field anymore.
        Employee employee = Employee.builder().userId(directReportId).fullName("Report One")
                .user(User.builder().id(directReportId).build()).weeklyOffPolicy(staleCachedPolicy).build();
        when(employeeRepository.findAllById(List.of(directReportId))).thenReturn(List.of(employee));
        when(penalizationPolicyResolutionService.resolveCurrentPolicyIdsByEmployee(any())).thenReturn(Map.of());
        com.nforce.onehr.entity.EmployeeWeeklyOffPolicyAssignment currentAssignment =
                com.nforce.onehr.entity.EmployeeWeeklyOffPolicyAssignment.builder()
                        .employeeUserId(directReportId).weeklyOffPolicy(authoritativePolicy).effectiveFrom(LocalDate.now().minusDays(5)).build();
        when(employeeWeeklyOffPolicyAssignmentRepository
                .findByEmployeeUserIdInAndEffectiveFromLessThanEqualOrderByEmployeeUserIdAscEffectiveFromDesc(any(), any()))
                .thenReturn(List.of(currentAssignment));
        com.nforce.onehr.entity.EmployeeWeeklyOffPolicyAssignment pendingAssignment =
                com.nforce.onehr.entity.EmployeeWeeklyOffPolicyAssignment.builder()
                        .employeeUserId(directReportId).weeklyOffPolicy(staleCachedPolicy).effectiveFrom(today.plusDays(10)).build();
        when(employeeWeeklyOffPolicyAssignmentRepository
                .findByEmployeeUserIdInAndEffectiveFromGreaterThanOrderByEmployeeUserIdAscEffectiveFromAsc(any(), any()))
                .thenReturn(List.of(pendingAssignment));

        List<EmployeeAssignmentRow> matching =
                service.listTeamAssignments(managerEmail, null, authoritativePolicy.getId(), null, null, null, null);
        assertEquals(1, matching.size());
        assertEquals("Actual Current Policy", matching.get(0).getWeeklyOffPolicyName());
        assertEquals(LocalDate.now().minusDays(5), matching.get(0).getWeeklyOffPolicyEffectiveSince());
        assertEquals("Stale Cache Policy", matching.get(0).getPendingWeeklyOffPolicyName());
        assertEquals(today.plusDays(10), matching.get(0).getPendingWeeklyOffPolicyEffectiveFrom());

        // Filtering by the STALE Employee.weeklyOffPolicy cache value does not match, since it no
        // longer reflects what actually governs this employee.
        List<EmployeeAssignmentRow> stale =
                service.listTeamAssignments(managerEmail, null, staleCachedPolicy.getId(), null, null, null, null);
        assertTrue(stale.isEmpty(), "filter must not match the stale Employee.weeklyOffPolicy display cache");
    }

    // ── Phase 3 verification audit: CSV import had zero test coverage — covering the 5-column
    //    schema (employee_code,shift_name,shift_effective_from,weekly_off_policy_name,
    //    weekly_off_effective_from), including the "old 4-column CSV" compatibility scenario. ──

    private MockMultipartFile csv(String... rows) {
        String content = "employee_code,shift_name,shift_effective_from,weekly_off_policy_name,weekly_off_effective_from\n"
                + String.join("\n", rows);
        return new MockMultipartFile("file", "import.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void importShiftsAndWeeklyOffs_succeeds_forShiftAndWeeklyOffWithBothEffectiveDates() throws Exception {
        Employee employee = Employee.builder().userId(directReportId).employeeCode("NF-1").build();
        when(employeeRepository.findByEmployeeCode("NF-1")).thenReturn(Optional.of(employee));
        Shift shift = newShift("Night Shift");
        when(shiftRepository.findByName("Night Shift")).thenReturn(Optional.of(shift));
        WeeklyOffPolicy policy = WeeklyOffPolicy.builder().id(UUID.randomUUID()).name("Standard").offDays("SATURDAY,SUNDAY").build();
        when(weeklyOffPolicyRepository.findByName("Standard")).thenReturn(Optional.of(policy));

        ImportResultResponse result = service.importShiftsAndWeeklyOffs(managerEmail,
                csv("NF-1,Night Shift," + tomorrow + ",Standard," + tomorrow));

        assertEquals(1, result.getSucceeded());
        assertEquals(0, result.getFailed());
        verify(employeeShiftAssignmentRepository).save(argThat(a -> a.getEffectiveFrom().equals(tomorrow)));
        verify(employeeWeeklyOffPolicyAssignmentRepository).save(argThat(a -> a.getEffectiveFrom().equals(tomorrow)));
    }

    @Test
    void importShiftsAndWeeklyOffs_rejectsRow_whenWeeklyOffPolicyNameSetButEffectiveDateMissing() throws Exception {
        // The exact "old 4-column CSV" compatibility scenario: a pre-Phase-3 template that sets
        // weekly_off_policy_name with no 5th column at all.
        Employee employee = Employee.builder().userId(directReportId).employeeCode("NF-1").build();
        when(employeeRepository.findByEmployeeCode("NF-1")).thenReturn(Optional.of(employee));
        WeeklyOffPolicy policy = WeeklyOffPolicy.builder().id(UUID.randomUUID()).name("Standard").offDays("SATURDAY,SUNDAY").build();
        when(weeklyOffPolicyRepository.findByName("Standard")).thenReturn(Optional.of(policy));

        ImportResultResponse result = service.importShiftsAndWeeklyOffs(managerEmail, csv("NF-1,,,Standard"));

        assertEquals(0, result.getSucceeded());
        assertEquals(1, result.getFailed());
        assertTrue(result.getResults().get(0).getError().contains("weekly_off_effective_from is required"));
        verifyNoInteractions(employeeWeeklyOffPolicyAssignmentRepository);
    }

    @Test
    void importShiftsAndWeeklyOffs_blankWeeklyOffColumn_leavesItUntouched_evenWithAStrayEffectiveDateValue() throws Exception {
        // A blank name column means "leave untouched" regardless of what's in the paired date
        // column — mirrors the existing shift_name/shift_effective_from convention exactly.
        Employee employee = Employee.builder().userId(directReportId).employeeCode("NF-1").build();
        when(employeeRepository.findByEmployeeCode("NF-1")).thenReturn(Optional.of(employee));

        ImportResultResponse result = service.importShiftsAndWeeklyOffs(managerEmail, csv("NF-1,,,," + tomorrow));

        assertEquals(1, result.getSucceeded());
        verifyNoInteractions(employeeWeeklyOffPolicyAssignmentRepository);
    }

    @Test
    void importShiftsAndWeeklyOffs_rejectsUnknownWeeklyOffPolicyName() throws Exception {
        Employee employee = Employee.builder().userId(directReportId).employeeCode("NF-1").build();
        when(employeeRepository.findByEmployeeCode("NF-1")).thenReturn(Optional.of(employee));
        when(weeklyOffPolicyRepository.findByName("Ghost Policy")).thenReturn(Optional.empty());

        ImportResultResponse result = service.importShiftsAndWeeklyOffs(managerEmail,
                csv("NF-1,,,Ghost Policy," + tomorrow));

        assertEquals(1, result.getFailed());
        assertTrue(result.getResults().get(0).getError().contains("Unknown weekly off policy"));
    }

    @Test
    void importShiftsAndWeeklyOffs_rejectsPastWeeklyOffEffectiveDate() throws Exception {
        Employee employee = Employee.builder().userId(directReportId).employeeCode("NF-1").build();
        when(employeeRepository.findByEmployeeCode("NF-1")).thenReturn(Optional.of(employee));
        WeeklyOffPolicy policy = WeeklyOffPolicy.builder().id(UUID.randomUUID()).name("Standard").offDays("SATURDAY,SUNDAY").build();
        when(weeklyOffPolicyRepository.findByName("Standard")).thenReturn(Optional.of(policy));

        ImportResultResponse result = service.importShiftsAndWeeklyOffs(managerEmail,
                csv("NF-1,,,Standard," + today.minusDays(1)));

        assertEquals(1, result.getFailed());
        assertTrue(result.getResults().get(0).getError().contains("cannot be in the past"));
        verifyNoInteractions(employeeWeeklyOffPolicyAssignmentRepository);
    }

    @Test
    void importShiftsAndWeeklyOffs_rejectsWeeklyOffEffectiveDateBeforeJoiningDate() throws Exception {
        LocalDate futureJoining = tomorrow.plusDays(10);
        Employee employee = Employee.builder().userId(directReportId).employeeCode("NF-1").joiningDate(futureJoining).build();
        when(employeeRepository.findByEmployeeCode("NF-1")).thenReturn(Optional.of(employee));
        WeeklyOffPolicy policy = WeeklyOffPolicy.builder().id(UUID.randomUUID()).name("Standard").offDays("SATURDAY,SUNDAY").build();
        when(weeklyOffPolicyRepository.findByName("Standard")).thenReturn(Optional.of(policy));

        ImportResultResponse result = service.importShiftsAndWeeklyOffs(managerEmail,
                csv("NF-1,,,Standard," + tomorrow));

        assertEquals(1, result.getFailed());
        assertTrue(result.getResults().get(0).getError().contains("joining date"));
        verifyNoInteractions(employeeWeeklyOffPolicyAssignmentRepository);
    }

    @Test
    void importShiftsAndWeeklyOffs_rejectsEmployeeNotACurrentDirectReport() throws Exception {
        Employee stranger = Employee.builder().userId(strangerId).employeeCode("NF-9").build();
        when(employeeRepository.findByEmployeeCode("NF-9")).thenReturn(Optional.of(stranger));

        ImportResultResponse result = service.importShiftsAndWeeklyOffs(managerEmail,
                csv("NF-9,,,Standard," + tomorrow));

        assertEquals(1, result.getFailed());
        assertTrue(result.getResults().get(0).getError().contains("Not a current direct report"));
    }
}
