package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.AttendanceRequestResponse;
import com.nforce.onehr.dto.attendance.CreateAttendanceRequest;
import com.nforce.onehr.entity.AttendanceRequest;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendanceRequestRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests for Partial Day's monthly-hours allowance (2h/month, spendable on any
 * day(s) within the month). It's advisory only: submit() never rejects for being over it (the
 * frontend confirms with the employee first, and the assigned approver makes the real call) —
 * getPartialDayBalance is what reports usage-vs-allowance for that confirmation prompt.
 * Mirrors RegularizationServiceTest's isolation approach.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceRequestServiceTest {

    @Mock private AttendanceRequestRepository requestRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;

    @InjectMocks private AttendanceRequestService service;

    private final UUID employeeId = UUID.randomUUID();
    private final String employeeEmail = "employee@test.com";

    @BeforeEach
    void setUp() {
        Role employeeRole = Role.builder().id(1).code("EMPLOYEE").displayName("Employee").build();
        User employeeUser = User.builder().id(employeeId).email(employeeEmail).roles(Set.of(employeeRole)).build();
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(java.util.Optional.of(employeeUser));
        lenient().when(requestRepository.save(any(AttendanceRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        // toResponse() looks these up defensively — absent is fine, it degrades to nulls/"Unknown".
        lenient().when(employeeRepository.findById(any())).thenReturn(java.util.Optional.empty());
        lenient().when(userRepository.findById(any())).thenReturn(java.util.Optional.empty());
    }

    private CreateAttendanceRequest partialDayRequest(LocalDate date, double hours) {
        return CreateAttendanceRequest.builder()
                .requestType("PARTIAL_DAY")
                .requestDate(date)
                .partialDayHours(BigDecimal.valueOf(hours))
                .partialDayMode("LATE_ARRIVE")
                .reason("test")
                .build();
    }

    private AttendanceRequest existing(LocalDate date, double hours, String status) {
        return AttendanceRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .requestType("PARTIAL_DAY")
                .requestDate(date)
                .partialDayHours(BigDecimal.valueOf(hours))
                .status(status)
                .build();
    }

    @Test
    void allowsPartialDayRequestWithinTheAdvisoryAllowance() {
        AttendanceRequestResponse response = service.submit(partialDayRequest(LocalDate.of(2026, 8, 3), 2), employeeEmail);

        assertEquals(BigDecimal.valueOf(2.0), response.getPartialDayHours());
    }

    @Test
    void allowsPartialDayRequestThatExceedsTheAdvisoryAllowance() {
        // The allowance is advisory (see getPartialDayBalance) — submit() itself never rejects
        // for being over it; the frontend confirms with the employee first, and the assigned
        // approver makes the real call.
        AttendanceRequestResponse response = service.submit(partialDayRequest(LocalDate.of(2026, 8, 9), 3), employeeEmail);

        assertEquals(BigDecimal.valueOf(3.0), response.getPartialDayHours());
    }

    @Test
    void rejectsZeroOrNegativePartialDayHours() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.submit(partialDayRequest(LocalDate.of(2026, 8, 9), 0), employeeEmail));
        assertTrue(ex.getMessage().contains("greater than zero"));
    }

    @Test
    void wfhRequestsAreUnaffectedByThePartialDayAllowance() {
        CreateAttendanceRequest wfh = CreateAttendanceRequest.builder()
                .requestType("WFH")
                .requestDate(LocalDate.of(2026, 8, 3))
                .reason("test")
                .build();

        AttendanceRequestResponse response = service.submit(wfh, employeeEmail);

        assertNull(response.getPartialDayHours());
        verify(requestRepository, never()).findByEmployeeUserIdAndRequestTypeAndRequestDateBetween(any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- getPartialDayBalance

    @Test
    void balanceCountsPendingRequestsTowardUsage() {
        when(requestRepository.findByEmployeeUserIdAndRequestTypeAndRequestDateBetween(
                eq(employeeId), eq("PARTIAL_DAY"), any(), any()))
                .thenReturn(List.of(existing(LocalDate.of(2026, 8, 3), 1.0, "PENDING")));

        AttendanceRequestService.PartialDayBalance balance =
                service.getPartialDayBalance(employeeEmail, LocalDate.of(2026, 8, 20));

        assertEquals(BigDecimal.valueOf(1.0), balance.usedHours());
        assertEquals(BigDecimal.valueOf(1.0), balance.remainingHours());
    }

    @Test
    void balanceExcludesRejectedRequests() {
        when(requestRepository.findByEmployeeUserIdAndRequestTypeAndRequestDateBetween(
                eq(employeeId), eq("PARTIAL_DAY"), any(), any()))
                .thenReturn(List.of(existing(LocalDate.of(2026, 8, 3), 2.0, "REJECTED")));

        AttendanceRequestService.PartialDayBalance balance =
                service.getPartialDayBalance(employeeEmail, LocalDate.of(2026, 8, 20));

        assertEquals(0, balance.usedHours().compareTo(BigDecimal.ZERO));
        assertEquals(0, balance.remainingHours().compareTo(BigDecimal.valueOf(2.0)));
    }

    @Test
    void balanceReflectsApprovedUsageEvenPastTheAllowance() {
        when(requestRepository.findByEmployeeUserIdAndRequestTypeAndRequestDateBetween(
                eq(employeeId), eq("PARTIAL_DAY"), any(), any()))
                .thenReturn(List.of(existing(LocalDate.of(2026, 8, 3), 2.5, "APPROVED")));

        AttendanceRequestService.PartialDayBalance balance =
                service.getPartialDayBalance(employeeEmail, LocalDate.of(2026, 8, 20));

        assertEquals(BigDecimal.valueOf(2.5), balance.usedHours());
        assertEquals(BigDecimal.valueOf(0).setScale(1), balance.remainingHours().setScale(1));
    }
}
