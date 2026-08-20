package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.CreateLeaveRequestRequest;
import com.nforce.onehr.dto.LeaveRequestResponse;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveRequestRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests — deliberately avoid @SpringBootTest/H2 here. The
 * repo's H2 test profile predates this change and can't create schema for the
 * citext-typed entities (User/Department/Designation/Location), so any test
 * that boots the real ApplicationContext against it fails on unrelated tables.
 * Fixing that is a separate, app-wide concern; this suite tests LeaveService
 * in isolation with mocked repositories instead.
 */
@ExtendWith(MockitoExtension.class)
class LeaveServiceTest {

    // Mirrors LeaveService.SAME_DAY_BLOCKING_STATUSES — kept as a separate constant here since
    // that one is private to the service.
    private static final Set<String> SAME_DAY_BLOCKING_STATUSES_FOR_TEST = Set.of("PENDING", "APPROVED");

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private LeaveTypeRepository leaveTypeRepository;
    @Mock private LeaveBalanceRepository leaveBalanceRepository;
    @Mock private LeaveRequestRepository leaveRequestRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private NotificationService notificationService;
    @Mock private AttendanceProperties attendanceProperties;

    @InjectMocks private LeaveService leaveService;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID managerId = UUID.randomUUID();
    private final UUID strangerId = UUID.randomUUID();
    private final String employeeEmail = "employee@test.com";
    private final String managerEmail = "manager@test.com";
    private final String strangerEmail = "stranger@test.com";

    private User employeeUser;
    private User managerUser;
    private User strangerUser;
    private LeaveType annual;
    private LeaveType sick;
    private LeaveType casual;

    @BeforeEach
    void setUp() {
        employeeUser = User.builder().id(employeeId).email(employeeEmail).build();
        managerUser = User.builder().id(managerId).email(managerEmail).build();
        strangerUser = User.builder().id(strangerId).email(strangerEmail).build();
        annual = LeaveType.builder().id(UUID.randomUUID()).code("ANNUAL").name("Annual Leave").build();
        sick = LeaveType.builder().id(UUID.randomUUID()).code("SICK").name("Sick Leave").build();
        casual = LeaveType.builder().id(UUID.randomUUID()).code("CASUAL").name("Casual Leave").build();

        // employeeName() falls back to userRepository when there's no Employee row —
        // stub loosely (lenient) so tests that don't inspect names don't need it repeated.
        lenient().when(employeeRepository.findById(any())).thenReturn(Optional.empty());
        lenient().when(userRepository.findById(employeeId)).thenReturn(Optional.of(employeeUser));
        lenient().when(userRepository.findById(managerId)).thenReturn(Optional.of(managerUser));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        // Annual/Sick/Casual form the consolidated balance group (see LeaveService
        // #isAnnualBalanceLeaveType) — findAll() backs #annualBalanceGroupTypeIds, and
        // findByCode("ANNUAL") backs #annualLeaveType, both reached any time a grouped type's
        // balance is looked up (submit/approve) or its available balance is calculated.
        lenient().when(leaveTypeRepository.findAll()).thenReturn(List.of(annual, sick, casual));
        lenient().when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        // Default: no other PENDING requests reserving balance — tests that care about pending
        // reservation override this explicitly.
        lenient().when(leaveRequestRepository.sumTotalDaysByEmployeeUserIdAndLeaveTypeIdInAndStatusAndStartDateBetween(
                        any(), any(), eq("PENDING"), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        // Pinned to the JVM's own default zone (not a hardcoded business zone like
        // "Asia/Kolkata") so LeaveService's zone-aware "today" always matches this test's own
        // LocalDate.now() calls, regardless of which machine/CI runner executes the suite.
        lenient().when(attendanceProperties.getZone()).thenReturn(ZoneId.systemDefault().getId());
    }

    /** Stubs the PENDING-days-reserved sum for the fixture's employee/year (any group of type IDs). */
    private void stubPendingReserved(BigDecimal days) {
        when(leaveRequestRepository.sumTotalDaysByEmployeeUserIdAndLeaveTypeIdInAndStatusAndStartDateBetween(
                        eq(employeeId), any(), eq("PENDING"), any(), any()))
                .thenReturn(days);
    }

    private CreateLeaveRequestRequest request(LocalDate start, LocalDate end, boolean halfDay, String reason) {
        return request("ANNUAL", start, end, halfDay, reason);
    }

    private CreateLeaveRequestRequest request(String leaveTypeCode, LocalDate start, LocalDate end, boolean halfDay, String reason) {
        CreateLeaveRequestRequest req = new CreateLeaveRequestRequest();
        req.setLeaveTypeCode(leaveTypeCode);
        req.setStartDate(start);
        req.setEndDate(end);
        req.setHalfDay(halfDay);
        req.setReason(reason);
        return req;
    }

    private LeaveBalance balanceOf(BigDecimal total, BigDecimal used) {
        return LeaveBalance.builder().employeeUserId(employeeId).leaveType(annual)
                .year(LocalDate.now().getYear()).totalDays(total).usedDays(used).build();
    }

    @Test
    void submitRequest_createsPendingRequest_withoutTouchingBalance() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        LeaveBalance balance = balanceOf(new BigDecimal("20"), BigDecimal.ZERO);
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balance));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> {
            LeaveRequest r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        LocalDate start = LocalDate.now().plusDays(5);
        LeaveRequestResponse resp = leaveService.submitRequest(request(start, start.plusDays(2), false, "Vacation"), employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        assertEquals(new BigDecimal("3"), resp.getTotalDays()); // inclusive day count
        verify(leaveBalanceRepository, never()).save(any());
        verify(auditService).log(employeeId, "LEAVE_REQUEST_SUBMITTED", resp.getId());
    }

    @Test
    void submitRequest_halfDay_countsAsHalfDay() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("20"), BigDecimal.ZERO)));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalDate day = LocalDate.now().plusDays(1);
        LeaveRequestResponse resp = leaveService.submitRequest(request(day, day, true, "Doctor"), employeeEmail);

        assertEquals(new BigDecimal("0.5"), resp.getTotalDays());
    }

    @Test
    void submitRequest_halfDayAcrossMultipleDates_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));

        LocalDate start = LocalDate.now();
        assertThrows(IllegalArgumentException.class,
                () -> leaveService.submitRequest(request(start, start.plusDays(1), true, "x"), employeeEmail));
    }

    @Test
    void submitRequest_exceedingBalance_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("2"), BigDecimal.ZERO)));

        LocalDate start = LocalDate.now();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> leaveService.submitRequest(request(start, start.plusDays(5), false, "Too long"), employeeEmail));
        assertEquals("Leave request exceeds your available Annual Leave balance of 2 days.", ex.getMessage());
        verify(leaveRequestRepository, never()).save(any());
    }

    // ── Status-aware annual-leave-limit enforcement ─────────────────────────────────────────
    //
    // No CANCELLED status and no update/edit endpoint exist anywhere in this codebase for leave
    // (confirmed by repo-wide search) — only PENDING, APPROVED, REJECTED. Per the existing status
    // model, only these three are covered below; nothing was invented for the other two.

    @Test
    void submitRequest_exactlyEqualToAvailableBalance_isAccepted() {
        // Boundary: requested == available must ACCEPT, not reject (item 2/15 — quota fully used
        // but not exceeded).
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("15"), new BigDecimal("11"))));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalDate start = LocalDate.now();
        // 15 - 11 = 4 available; request exactly 4 days (inclusive 4-day range).
        LeaveRequestResponse resp = leaveService.submitRequest(request(start, start.plusDays(3), false, "Exact fit"), employeeEmail);

        assertEquals("PENDING", resp.getStatus());
    }

    @Test
    void submitRequest_oneDayAboveAvailableBalance_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("15"), new BigDecimal("11"))));

        LocalDate start = LocalDate.now();
        // 4 available; requesting 5 (one day over) must be rejected.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> leaveService.submitRequest(request(start, start.plusDays(4), false, "One day too many"), employeeEmail));
        assertEquals("Leave request exceeds your available Annual Leave balance of 4 days.", ex.getMessage());
        verify(leaveRequestRepository, never()).save(any());
    }

    @Test
    void submitRequest_halfDayRequest_exactlyEqualToHalfDayAvailableBalance_isAccepted() {
        // Fractional boundary using the actual half-day duration path (0.5), not a whole-day
        // range standing in for a fraction.
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("15"), new BigDecimal("14.5"))));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalDate day = LocalDate.now();
        // 15 - 14.5 = 0.5 available; a half-day request exactly matches it.
        LeaveRequestResponse resp = leaveService.submitRequest(request(day, day, true, "Last half day"), employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        assertEquals(new BigDecimal("0.5"), resp.getTotalDays());
    }

    @Test
    void submitRequest_halfDayRequest_exceedingZeroBalance_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("15"), new BigDecimal("15"))));

        LocalDate day = LocalDate.now();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> leaveService.submitRequest(request(day, day, true, "No balance left"), employeeEmail));
        assertEquals("Leave request exceeds your available Annual Leave balance of 0 days.", ex.getMessage());
    }

    @Test
    void submitRequest_existingPendingRequestsReserveBalance_blockingASecondRequestThatWouldExceedQuota() {
        // Core requirement: PENDING is reserved, so two requests that individually fit can still
        // collectively exceed quota and must be blocked on the second submission.
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("15"), BigDecimal.ZERO)));
        stubPendingReserved(new BigDecimal("13")); // an earlier PENDING request already reserved 13 of 15

        LocalDate start = LocalDate.now();
        // Only 2 days available (15 - 0 - 13); requesting 3 must be rejected.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> leaveService.submitRequest(request(start, start.plusDays(2), false, "Third request"), employeeEmail));
        assertEquals("Leave request exceeds your available Annual Leave balance of 2 days.", ex.getMessage());
        verify(leaveRequestRepository, never()).save(any());
    }

    @Test
    void submitRequest_pendingPlusApproved_cannotCollectivelyExceedQuota() {
        // Approved (via usedDays) and Pending (via the reserved-sum) are both subtracted from the
        // same quota — neither alone tells the whole story.
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("15"), new BigDecimal("5")))); // 5 approved
        stubPendingReserved(new BigDecimal("6")); // 6 pending
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalDate start = LocalDate.now();
        // Available = 15 - 5 - 6 = 4. Requesting 4 succeeds, 4.5 fails (matches section 5 example).
        LeaveRequestResponse ok = leaveService.submitRequest(request(start, start.plusDays(3), false, "Fits exactly"), employeeEmail);
        assertEquals("PENDING", ok.getStatus());
    }

    @Test
    void submitRequest_pendingPlusApproved_overLimit_isRejected() {
        // Exact numbers from the spec's section-5 example: approved=5, pending=6 -> available=4.
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("15"), new BigDecimal("5"))));
        stubPendingReserved(new BigDecimal("6"));

        LocalDate start = LocalDate.now();
        // Available = 4; requesting 5 days (one over) must be rejected — the real-request
        // equivalent of the spec's "4.5 days -> REJECT" (a single request's duration is always a
        // whole-day range or exactly 0.5, per the existing duration calculation; any amount over
        // the available 4 is rejected the same way).
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> leaveService.submitRequest(request(start, start.plusDays(4), false, "Over by one"), employeeEmail));
        assertEquals("Leave request exceeds your available Annual Leave balance of 4 days.", ex.getMessage());
    }

    @Test
    void submitRequest_rejectedRequestsDoNotReserveBalance() {
        // A REJECTED request's days were never added to the PENDING-reserved sum in the first
        // place (the repository query filters status = 'PENDING'), so a full-quota request must
        // still succeed even though a same-size request was previously rejected.
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("15"), BigDecimal.ZERO)));
        stubPendingReserved(BigDecimal.ZERO); // the earlier REJECTED request contributes nothing here
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalDate start = LocalDate.now();
        LeaveRequestResponse resp = leaveService.submitRequest(
                request(start, start.plusDays(14), false, "Full quota, previous request was rejected"), employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        assertEquals(new BigDecimal("15"), resp.getTotalDays());
    }

    @Test
    void submitRequest_zeroAvailableBalance_blocksAnyNewRequest() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("15"), new BigDecimal("15")))); // fully consumed

        LocalDate day = LocalDate.now();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> leaveService.submitRequest(request(day, day, true, "Half day"), employeeEmail));
        assertEquals("Leave request exceeds your available Annual Leave balance of 0 days.", ex.getMessage());
    }

    // ── Previous-date and same-day duplicate restrictions ───────────────────────────────────

    @Test
    void submitRequest_startDateInThePast_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));

        LocalDate yesterday = LocalDate.now().minusDays(1);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> leaveService.submitRequest(request(yesterday, yesterday, false, "Too late"), employeeEmail));
        assertEquals("Leave cannot be requested for a date before today", ex.getMessage());
        verify(leaveRequestRepository, never()).save(any());
    }

    @Test
    void submitRequest_today_withNoExistingRequestForToday_isAllowed() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("20"), BigDecimal.ZERO)));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        // existsBy...(PENDING, APPROVED) is unstubbed here -> defaults to false, i.e. no
        // existing request for today.

        LocalDate today = LocalDate.now();
        LeaveRequestResponse resp = leaveService.submitRequest(request(today, today, false, "Same day"), employeeEmail);

        assertEquals("PENDING", resp.getStatus());
    }

    @Test
    void submitRequest_today_withExistingPendingRequestForToday_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        LocalDate today = LocalDate.now();
        when(leaveRequestRepository.existsByEmployeeUserIdAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        eq(employeeId), eq(SAME_DAY_BLOCKING_STATUSES_FOR_TEST), eq(today), eq(today)))
                .thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> leaveService.submitRequest(request(today, today, false, "Second request today"), employeeEmail));
        assertEquals("You already have a pending or approved leave request for today", ex.getMessage());
        verify(leaveRequestRepository, never()).save(any());
    }

    @Test
    void submitRequest_today_withExistingApprovedRequestForToday_isRejected() {
        // Same repository call as the PENDING case above (the query checks status IN
        // (PENDING, APPROVED) in one shot) — covered separately since both statuses are an
        // explicit, independent requirement.
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        LocalDate today = LocalDate.now();
        when(leaveRequestRepository.existsByEmployeeUserIdAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        eq(employeeId), eq(SAME_DAY_BLOCKING_STATUSES_FOR_TEST), eq(today), eq(today)))
                .thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> leaveService.submitRequest(request(today, today, false, "Second request today"), employeeEmail));
        assertEquals("You already have a pending or approved leave request for today", ex.getMessage());
        verify(leaveRequestRepository, never()).save(any());
    }

    @Test
    void submitRequest_today_withOnlyRejectedRequestForToday_isAllowed() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("20"), BigDecimal.ZERO)));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        // A REJECTED-only day never matches the (PENDING, APPROVED) status filter, so the
        // repository call correctly returns false (default, left unstubbed) here.

        LocalDate today = LocalDate.now();
        LeaveRequestResponse resp = leaveService.submitRequest(
                request(today, today, false, "Retry after earlier rejection"), employeeEmail);

        assertEquals("PENDING", resp.getStatus());
    }

    @Test
    void submitRequest_futureDate_existingBalanceAndDayCountBehaviorIsUnaffected() {
        // Future dates never pass through either new guard (isBefore(today) is false, and
        // isEqual(today) is false) — this only re-confirms the pre-existing behavior still holds.
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("20"), BigDecimal.ZERO)));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalDate start = LocalDate.now().plusDays(10);
        LeaveRequestResponse resp = leaveService.submitRequest(request(start, start.plusDays(1), false, "Future trip"), employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        assertEquals(new BigDecimal("2"), resp.getTotalDays());
        verify(leaveRequestRepository, never())
                .existsByEmployeeUserIdAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(any(), any(), any(), any());
    }

    @Test
    void approve_pendingToApproved_doesNotDoubleCountReservedBalance() {
        // Once a request is APPROVED, the PENDING-reserved query no longer counts it (its status
        // changed), and usedDays picks it up exactly once instead — no double consumption.
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now().plusDays(3))
                .totalDays(new BigDecimal("4")).status("PENDING").employeeReason("Trip").build();
        LeaveBalance balance = balanceOf(new BigDecimal("15"), BigDecimal.ZERO);

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balance));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        leaveService.approve(pending.getId(), managerEmail);

        // usedDays incremented exactly once by the 4-day request — not doubled with any
        // still-pending reservation for the same request.
        assertEquals(new BigDecimal("4"), balance.getUsedDays());
    }

    @Test
    void submitRequest_afterRejection_releasesReservedBalanceForNextSubmission() {
        // PENDING -> REJECTED must release the reservation: a request that would have been
        // blocked while the earlier one was still PENDING must succeed once it's REJECTED.
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("15"), BigDecimal.ZERO)));
        // The earlier request is now REJECTED, so the reserved-sum query (which only ever sums
        // status='PENDING') correctly reports zero — simulating the post-rejection state.
        stubPendingReserved(BigDecimal.ZERO);
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalDate start = LocalDate.now();
        LeaveRequestResponse resp = leaveService.submitRequest(
                request(start, start.plusDays(14), false, "Now fits, prior request was rejected"), employeeEmail);

        assertEquals("PENDING", resp.getStatus());
    }

    @Test
    void submitRequest_multipleApprovedRequests_cannotExceedQuota() {
        // Two prior APPROVED requests already accumulated in usedDays (10) leave only 5 — a
        // third request for 6 must be rejected.
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("15"), new BigDecimal("10"))));

        LocalDate start = LocalDate.now();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> leaveService.submitRequest(request(start, start.plusDays(5), false, "Sixth day over"), employeeEmail));
        assertEquals("Leave request exceeds your available Annual Leave balance of 5 days.", ex.getMessage());
    }

    @Test
    void submitRequest_noExistingLeaveRequests_usesFullQuotaAsAvailable() {
        // Employee with no APPROVED/PENDING history — available balance is the full quota.
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("24"), BigDecimal.ZERO)));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalDate start = LocalDate.now();
        LeaveRequestResponse resp = leaveService.submitRequest(
                request(start, start.plusDays(23), false, "Full 24-day quota"), employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        assertEquals(new BigDecimal("24"), resp.getTotalDays());
    }

    @Test
    void approve_byCurrentManager_decrementsBalanceAndRecordsDecision() {
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("4")).status("PENDING").employeeReason("Trip").build();
        LeaveBalance balance = balanceOf(new BigDecimal("20"), BigDecimal.ZERO);

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balance));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        java.time.LocalDateTime before = java.time.LocalDateTime.now(ZoneId.systemDefault());
        LeaveRequestResponse approved = leaveService.approve(pending.getId(), managerEmail);
        java.time.LocalDateTime after = java.time.LocalDateTime.now(ZoneId.systemDefault());

        assertEquals("APPROVED", approved.getStatus());
        assertEquals(managerId, pending.getDecidedBy());
        assertNotNull(approved.getDecidedAt());
        // decidedAt must be the backend/application system time captured at the moment of the
        // approval action — bounded between timestamps taken immediately before and after the
        // call, in the same (test-configured) zone as AttendanceProperties#getZone.
        assertFalse(approved.getDecidedAt().isBefore(before));
        assertFalse(approved.getDecidedAt().isAfter(after));
        assertEquals(new BigDecimal("4"), balance.getUsedDays());
        verify(leaveBalanceRepository).save(balance);
        verify(auditService).log(eq(managerId), eq("LEAVE_REQUEST_APPROVED"), eq(pending.getId()), any(), any());
        verify(notificationService, times(1)).send(eq(employeeId), eq("LEAVE_APPROVED"), any(), any(), any());
    }

    @Test
    void reject_byCurrentManager_requiresReasonAndLeavesBalanceUntouched() {
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("PENDING").employeeReason("Trip").build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        java.time.LocalDateTime before = java.time.LocalDateTime.now(ZoneId.systemDefault());
        LeaveRequestResponse rejected = leaveService.reject(pending.getId(), "Team coverage conflict", managerEmail);
        java.time.LocalDateTime after = java.time.LocalDateTime.now(ZoneId.systemDefault());

        assertEquals("REJECTED", rejected.getStatus());
        assertEquals("Team coverage conflict", rejected.getDecisionReason());
        assertEquals(managerId, pending.getDecidedBy());
        assertNotNull(rejected.getDecidedAt());
        assertFalse(rejected.getDecidedAt().isBefore(before));
        assertFalse(rejected.getDecidedAt().isAfter(after));
        verify(leaveBalanceRepository, never()).save(any());
        verify(auditService).log(eq(managerId), eq("LEAVE_REQUEST_REJECTED"), eq(pending.getId()), any(), any());
        verify(notificationService, times(1)).send(eq(employeeId), eq("LEAVE_REJECTED"), any(),
                contains("Team coverage conflict"), any());
    }

    @Test
    void approve_decidedAt_isGeneratedFromTheConfiguredApplicationTimezone_notClientOrJvmDefault() {
        // Prove decidedAt is derived from AttendanceProperties#getZone() (the existing
        // OneHR application timezone config used elsewhere, e.g. #submitRequest's "today"
        // resolution) rather than the JVM default zone or any client-supplied value.
        ZoneId configuredZone = ZoneId.of("UTC").equals(ZoneId.systemDefault())
                ? ZoneId.of("Asia/Kolkata") : ZoneId.of("UTC");
        when(attendanceProperties.getZone()).thenReturn(configuredZone.getId());

        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("PENDING").employeeReason("Trip").build();
        LeaveBalance balance = balanceOf(new BigDecimal("20"), BigDecimal.ZERO);

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balance));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        java.time.LocalDateTime before = java.time.LocalDateTime.now(configuredZone);
        LeaveRequestResponse approved = leaveService.approve(pending.getId(), managerEmail);
        java.time.LocalDateTime after = java.time.LocalDateTime.now(configuredZone);

        assertFalse(approved.getDecidedAt().isBefore(before));
        assertFalse(approved.getDecidedAt().isAfter(after));
    }

    // ── Leave notification flow ─────────────────────────────────────────────────────────────

    @Test
    void submitRequest_notifiesCurrentManager_andNeverNotifiesTheSubmitterThemselves() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("20"), BigDecimal.ZERO)));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalDate start = LocalDate.now().plusDays(5);
        leaveService.submitRequest(request(start, start.plusDays(2), false, "Vacation"), employeeEmail);

        // employeeRepository has no Employee row for this fixture (see setUp's lenient stub), so
        // employeeName() falls back to the actor's own email — asserted here instead of duplicating
        // that fallback logic.
        verify(notificationService).send(eq(managerId), eq("LEAVE_REQUEST_SUBMITTED"), any(),
                contains(employeeEmail), eq("/approvals?type=LEAVE"));
        verify(notificationService, never()).send(eq(employeeId), any(), any(), any(), any());
    }

    @Test
    void submitRequest_withNoManagerOnFile_sendsNoNotification() {
        // Mirrors AssetService/RegularizationService: submission notifies only the resolved
        // manager. With no manager on file (unstubbed historyRepository -> Optional.empty()),
        // there is nobody to notify — this must not throw, and must not fall back to notifying
        // HR/Super Admin instead (that broadcast was deliberately not built; see #notifySubmission).
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("20"), BigDecimal.ZERO)));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalDate start = LocalDate.now().plusDays(5);
        LeaveRequestResponse resp = leaveService.submitRequest(request(start, start.plusDays(2), false, "Vacation"), employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        verify(notificationService, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void submitRequest_failedValidation_neverSendsAnyNotification() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("2"), BigDecimal.ZERO)));

        LocalDate start = LocalDate.now();
        assertThrows(IllegalArgumentException.class,
                () -> leaveService.submitRequest(request(start, start.plusDays(5), false, "Too long"), employeeEmail));

        verify(notificationService, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void approve_employeeNotification_includesLeaveTypeDatesAndTotalDays() {
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.of(2026, 8, 20)).endDate(LocalDate.of(2026, 8, 22))
                .totalDays(new BigDecimal("3")).status("PENDING").employeeReason("Trip").build();
        LeaveBalance balance = balanceOf(new BigDecimal("20"), BigDecimal.ZERO);

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balance));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        leaveService.approve(pending.getId(), managerEmail);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).send(eq(employeeId), eq("LEAVE_APPROVED"), eq("Leave Request Approved"),
                messageCaptor.capture(), eq("/requests?type=LEAVE"));
        String message = messageCaptor.getValue();
        assertTrue(message.contains("Annual Leave"));
        assertTrue(message.contains("20 Aug 2026"));
        assertTrue(message.contains("22 Aug 2026"));
        assertTrue(message.contains("3 days"));
    }

    @Test
    void reject_notifiesEmployee_withRejectionReasonIncluded() {
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("PENDING").employeeReason("Trip").build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        leaveService.reject(pending.getId(), "Team coverage conflict", managerEmail);

        verify(notificationService).send(eq(employeeId), eq("LEAVE_REJECTED"), eq("Leave Request Rejected"),
                contains("Team coverage conflict"), eq("/requests?type=LEAVE"));
    }

    @Test
    void approve_alreadyDecided_neverSendsASecondApprovalNotification() {
        // Reprocessing an already-decided request must not create duplicate notifications — the
        // PENDING guard blocks it before any notification code (employee or admin) is reached.
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("PENDING").employeeReason("Trip").build();
        LeaveBalance balance = balanceOf(new BigDecimal("20"), BigDecimal.ZERO);

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balance));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        leaveService.approve(pending.getId(), managerEmail);
        assertThrows(IllegalStateException.class, () -> leaveService.approve(pending.getId(), managerEmail));

        verify(notificationService, times(1)).send(any(), eq("LEAVE_APPROVED"), any(), any(), any());
    }

    @Test
    void approve_byNonManager_isDenied() {
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("PENDING").employeeReason("Trip").build();

        when(userRepository.findByEmail(strangerEmail)).thenReturn(Optional.of(strangerUser));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));

        assertThrows(AccessDeniedException.class, () -> leaveService.approve(pending.getId(), strangerEmail));
        verify(leaveBalanceRepository, never()).save(any());
        verify(leaveRequestRepository, never()).save(any());
        verify(notificationService, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void approve_withNoManagerRelationship_isDenied() {
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("PENDING").employeeReason("Trip").build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.empty());

        assertThrows(AccessDeniedException.class, () -> leaveService.approve(pending.getId(), managerEmail));
    }

    @Test
    void approve_alreadyDecided_isRejected() {
        LeaveRequest decided = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("APPROVED").employeeReason("Trip").build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(leaveRequestRepository.findById(decided.getId())).thenReturn(Optional.of(decided));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));

        assertThrows(IllegalStateException.class, () -> leaveService.approve(decided.getId(), managerEmail));
        verify(leaveBalanceRepository, never()).save(any());
    }

    /**
     * A second approve() call on an already-decided request must not fire a second
     * notification (ONEHR-140) — the PENDING guard in approve() blocks it before the
     * notification call is ever reached.
     */
    @Test
    void approve_calledTwice_sendsNotificationOnlyOnce() {
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("PENDING").employeeReason("Trip").build();
        LeaveBalance balance = balanceOf(new BigDecimal("20"), BigDecimal.ZERO);

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balance));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        leaveService.approve(pending.getId(), managerEmail);
        assertThrows(IllegalStateException.class, () -> leaveService.approve(pending.getId(), managerEmail));

        verify(notificationService, times(1)).send(eq(employeeId), eq("LEAVE_APPROVED"), any(), any(), any());
    }

    @Test
    void listPendingApprovals_isScopedToCurrentDirectReportsOnly() {
        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(historyRepository.findByManagerUserIdAndEffectiveToIsNull(managerId))
                .thenReturn(List.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("PENDING").employeeReason("Trip").build();
        when(leaveRequestRepository.findByEmployeeUserIdInAndStatusOrderByCreatedAtAsc(List.of(employeeId), "PENDING"))
                .thenReturn(List.of(pending));

        List<LeaveRequestResponse> queue = leaveService.listPendingApprovals(managerEmail);

        assertEquals(1, queue.size());
        assertEquals(pending.getId(), queue.get(0).getId());
    }

    @Test
    void listPendingApprovals_withNoDirectReports_returnsEmptyWithoutQueryingRequests() {
        when(userRepository.findByEmail(strangerEmail)).thenReturn(Optional.of(strangerUser));
        when(historyRepository.findByManagerUserIdAndEffectiveToIsNull(strangerId)).thenReturn(List.of());

        List<LeaveRequestResponse> queue = leaveService.listPendingApprovals(strangerEmail);

        assertTrue(queue.isEmpty());
        verify(leaveRequestRepository, never()).findByEmployeeUserIdInAndStatusOrderByCreatedAtAsc(any(), any());
    }

    // ── HR Admin / Super Admin override (not the employee's reporting manager) ──

    private User userWithRole(UUID id, String email, String roleCode) {
        Role role = Role.builder().id(1).code(roleCode).displayName(roleCode).build();
        return User.builder().id(id).email(email).roles(Set.of(role)).build();
    }

    @Test
    void listPendingApprovals_forHrAdmin_returnsAllPendingRegardlessOfReportingLine() {
        User hrAdmin = userWithRole(strangerId, strangerEmail, "HR_ADMIN");
        when(userRepository.findByEmail(strangerEmail)).thenReturn(Optional.of(hrAdmin));
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("PENDING").employeeReason("Trip").build();
        when(leaveRequestRepository.findByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of(pending));

        List<LeaveRequestResponse> queue = leaveService.listPendingApprovals(strangerEmail);

        assertEquals(1, queue.size());
        assertEquals(pending.getId(), queue.get(0).getId());
        verify(historyRepository, never()).findByManagerUserIdAndEffectiveToIsNull(any());
    }

    @Test
    void listPendingApprovals_forSuperAdmin_returnsAllPendingRegardlessOfReportingLine() {
        User superAdmin = userWithRole(strangerId, strangerEmail, "SUPER_ADMIN");
        when(userRepository.findByEmail(strangerEmail)).thenReturn(Optional.of(superAdmin));
        when(leaveRequestRepository.findByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of());

        List<LeaveRequestResponse> queue = leaveService.listPendingApprovals(strangerEmail);

        assertTrue(queue.isEmpty());
        verify(historyRepository, never()).findByManagerUserIdAndEffectiveToIsNull(any());
    }

    @Test
    void approve_byHrAdmin_whoIsNotTheReportingManager_isAllowed() {
        User hrAdmin = userWithRole(strangerId, strangerEmail, "HR_ADMIN");
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("2")).status("PENDING").employeeReason("Trip").build();
        LeaveBalance balance = balanceOf(new BigDecimal("20"), BigDecimal.ZERO);

        when(userRepository.findByEmail(strangerEmail)).thenReturn(Optional.of(hrAdmin));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balance));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LeaveRequestResponse approved = leaveService.approve(pending.getId(), strangerEmail);

        assertEquals("APPROVED", approved.getStatus());
        assertEquals(strangerId, pending.getDecidedBy());
        assertEquals(new BigDecimal("2"), balance.getUsedDays());
        // Admin override must not even need to resolve the reporting-manager relationship.
        verify(historyRepository, never()).findByEmployeeUserIdAndEffectiveToIsNull(any());
        verify(auditService).log(eq(strangerId), eq("LEAVE_REQUEST_APPROVED"), eq(pending.getId()), any(), any());
    }

    @Test
    void reject_bySuperAdmin_whoIsNotTheReportingManager_isAllowed() {
        User superAdmin = userWithRole(strangerId, strangerEmail, "SUPER_ADMIN");
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("PENDING").employeeReason("Trip").build();

        when(userRepository.findByEmail(strangerEmail)).thenReturn(Optional.of(superAdmin));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LeaveRequestResponse rejected = leaveService.reject(pending.getId(), "Policy conflict", strangerEmail);

        assertEquals("REJECTED", rejected.getStatus());
        verify(leaveBalanceRepository, never()).save(any());
        verify(historyRepository, never()).findByEmployeeUserIdAndEffectiveToIsNull(any());
    }

    @Test
    void approve_byEmployeeLevelUser_withNoOverrideRoleAndNotTheManager_isDenied() {
        // strangerUser deliberately carries no roles (see setUp) — same shape as a plain
        // EMPLOYEE-level account: no HR_ADMIN/SUPER_ADMIN override and not the current manager.
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("PENDING").employeeReason("Trip").build();

        when(userRepository.findByEmail(strangerEmail)).thenReturn(Optional.of(strangerUser));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));

        assertThrows(AccessDeniedException.class, () -> leaveService.approve(pending.getId(), strangerEmail));
        verify(leaveBalanceRepository, never()).save(any());
        verify(leaveRequestRepository, never()).save(any());
    }
}
