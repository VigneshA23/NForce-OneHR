package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.AttendancePenaltyResponse;
import com.nforce.onehr.dto.attendance.PenaltyCancelResultResponse;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.AttendancePenaltyStatus;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.RegularizationRequest;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.RegularizationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Manager: Regularize & Cancel Penalties. Pure Mockito, same isolation approach as
 * AttendanceServiceTeamStatsTest — {@link AttendancePenaltyRepository#findAll(org.springframework.data.jpa.domain.Specification)}
 * is stubbed directly rather than exercising real Specification predicates (no DB in this test layer).
 */
@ExtendWith(MockitoExtension.class)
class AttendancePenaltyServiceTest {

    @Mock private AttendancePenaltyRepository attendancePenaltyRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository managerHistoryRepository;
    @Mock private RegularizationRequestRepository regularizationRequestRepository;
    @Mock private AuditService auditService;

    @InjectMocks private AttendancePenaltyService service;

    private final UUID managerId = UUID.randomUUID();
    private final UUID empId = UUID.randomUUID();
    private final UUID otherEmpId = UUID.randomUUID();
    private final String managerEmail = "manager@test.com";
    private final LocalDate incidentDate = LocalDate.of(2026, 8, 3);
    private final LocalDate from = LocalDate.of(2026, 8, 1);
    private final LocalDate to = LocalDate.of(2026, 8, 10);

    private Employee manager;
    private Employee employee;

    @BeforeEach
    void setUp() {
        manager = Employee.builder().userId(managerId).fullName("Manager One").build();
        employee = Employee.builder().userId(empId).fullName("Employee One").employeeCode("NF-1").build();
        when(employeeRepository.findByUser_Email(managerEmail)).thenReturn(Optional.of(manager));
        lenient().when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of(empId));
        lenient().when(employeeRepository.findAllByIdWithScheduleDetails(List.of(empId))).thenReturn(List.of(employee));
        lenient().when(regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(any(), any(), any()))
                .thenReturn(List.of());
    }

    private AttendancePenalty penalty(String status) {
        return AttendancePenalty.builder().id(UUID.randomUUID()).employeeUserId(empId)
                .incidentDate(incidentDate).discrepancyType("LATE_ARRIVAL").status(status)
                .evaluatedAt(LocalDateTime.now()).penalizedOn(LocalDateTime.now()).build();
    }

    @Test
    void list_noDirectReports_returnsEmpty() {
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of());

        List<AttendancePenaltyResponse> result = service.list(managerEmail, from, to, null, null, null, null, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void list_excludesPenaltiesWithAnActiveRegularization() {
        AttendancePenalty p = penalty(AttendancePenaltyStatus.PENDING_REVIEW);
        when(attendancePenaltyRepository.findAll((org.springframework.data.jpa.domain.Specification<AttendancePenalty>) any()))
                .thenReturn(List.of(p));
        when(regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(List.of(empId), from, to))
                .thenReturn(List.of(RegularizationRequest.builder()
                        .employeeUserId(empId).attendanceDate(incidentDate).status("PENDING").reason("x").build()));

        List<AttendancePenaltyResponse> result = service.list(managerEmail, from, to, null, null, null, null, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void list_includesPenalty_whenNoActiveRegularizationExists() {
        AttendancePenalty p = penalty(AttendancePenaltyStatus.PENDING_REVIEW);
        when(attendancePenaltyRepository.findAll((org.springframework.data.jpa.domain.Specification<AttendancePenalty>) any()))
                .thenReturn(List.of(p));

        List<AttendancePenaltyResponse> result = service.list(managerEmail, from, to, null, null, null, null, null);

        assertEquals(1, result.size());
        assertEquals(empId, result.get(0).getEmployeeUserId());
        assertTrue(result.get(0).isCancellable());
    }

    @Test
    void list_rejectedRegularization_doesNotBlockThePenalty() {
        AttendancePenalty p = penalty(AttendancePenaltyStatus.PENDING_REVIEW);
        when(attendancePenaltyRepository.findAll((org.springframework.data.jpa.domain.Specification<AttendancePenalty>) any()))
                .thenReturn(List.of(p));
        when(regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(List.of(empId), from, to))
                .thenReturn(List.of(RegularizationRequest.builder()
                        .employeeUserId(empId).attendanceDate(incidentDate).status("REJECTED").reason("x").build()));

        List<AttendancePenaltyResponse> result = service.list(managerEmail, from, to, null, null, null, null, null);

        assertEquals(1, result.size());
    }

    @Test
    void cancelBulk_partialSuccess_oneInvalidStatusDoesNotBlockTheOther() {
        AttendancePenalty cancellable = penalty(AttendancePenaltyStatus.APPLIED);
        AttendancePenalty alreadyCancelled = penalty(AttendancePenaltyStatus.CANCELLED);
        when(attendancePenaltyRepository.findById(cancellable.getId())).thenReturn(Optional.of(cancellable));
        when(attendancePenaltyRepository.findById(alreadyCancelled.getId())).thenReturn(Optional.of(alreadyCancelled));

        PenaltyCancelResultResponse result = service.cancelBulk(
                managerEmail, List.of(cancellable.getId(), alreadyCancelled.getId()), "policy waived");

        assertEquals(List.of(cancellable.getId()), result.getSucceededIds());
        assertEquals(1, result.getFailed().size());
        assertEquals(alreadyCancelled.getId(), result.getFailed().get(0).getId());
        assertEquals(AttendancePenaltyStatus.CANCELLED, cancellable.getStatus());
        assertEquals("policy waived", cancellable.getCancellationReason());
        assertEquals(managerId, cancellable.getCancelledBy());
    }

    @Test
    void cancelBulk_employeeNoLongerADirectReport_fails() {
        AttendancePenalty penaltyForOther = AttendancePenalty.builder().id(UUID.randomUUID()).employeeUserId(otherEmpId)
                .incidentDate(incidentDate).discrepancyType("LATE_ARRIVAL").status(AttendancePenaltyStatus.APPLIED)
                .evaluatedAt(LocalDateTime.now()).penalizedOn(LocalDateTime.now()).build();
        when(attendancePenaltyRepository.findById(penaltyForOther.getId())).thenReturn(Optional.of(penaltyForOther));

        PenaltyCancelResultResponse result = service.cancelBulk(managerEmail, List.of(penaltyForOther.getId()), "reason");

        assertTrue(result.getSucceededIds().isEmpty());
        assertEquals(1, result.getFailed().size());
    }

    @Test
    void cancelBulk_activeRegularizationExists_blocksCancellation() {
        AttendancePenalty p = penalty(AttendancePenaltyStatus.PENDING_REVIEW);
        when(attendancePenaltyRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(
                List.of(empId), incidentDate, incidentDate))
                .thenReturn(List.of(RegularizationRequest.builder()
                        .employeeUserId(empId).attendanceDate(incidentDate).status("APPROVED").reason("x").build()));

        PenaltyCancelResultResponse result = service.cancelBulk(managerEmail, List.of(p.getId()), "reason");

        assertTrue(result.getSucceededIds().isEmpty());
        assertEquals(1, result.getFailed().size());
        assertEquals(AttendancePenaltyStatus.PENDING_REVIEW, p.getStatus()); // unchanged
    }

    @Test
    void cancelBulk_penaltyNotFound_fails() {
        UUID missingId = UUID.randomUUID();
        when(attendancePenaltyRepository.findById(missingId)).thenReturn(Optional.empty());

        PenaltyCancelResultResponse result = service.cancelBulk(managerEmail, List.of(missingId), "reason");

        assertTrue(result.getSucceededIds().isEmpty());
        assertEquals(1, result.getFailed().size());
    }
}
