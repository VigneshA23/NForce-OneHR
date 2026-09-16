package com.nforce.onehr.service;

import com.nforce.onehr.dto.reports.AttendanceRequestReportRow;
import com.nforce.onehr.entity.AttendanceRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.OvertimeRequest;
import com.nforce.onehr.entity.RegularizationRequest;
import com.nforce.onehr.entity.WebClockInRequest;
import com.nforce.onehr.repository.AttendanceRequestRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.OvertimeRequestRepository;
import com.nforce.onehr.repository.RegularizationRequestRepository;
import com.nforce.onehr.repository.WebClockInRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Manager-scoped Attendance Request Reports (ONEHR-109) — same isolation approach as
 * AttendanceServicePunctualityTest (pure Mockito, no Spring context). Focus: the report can only
 * ever be built from the CALLING manager's own current direct reports (never a client-supplied
 * employee id, and never another manager's team), and each report type maps its own entity
 * fields onto the shared row DTO correctly.
 */
@ExtendWith(MockitoExtension.class)
class ReportsServiceTest {

    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository managerHistoryRepository;
    @Mock private RegularizationRequestRepository regularizationRequestRepository;
    @Mock private WebClockInRequestRepository webClockInRequestRepository;
    @Mock private OvertimeRequestRepository overtimeRequestRepository;
    @Mock private AttendanceRequestRepository attendanceRequestRepository;

    @InjectMocks private ReportsService reportsService;

    private final UUID managerId = UUID.randomUUID();
    private final UUID otherManagerId = UUID.randomUUID();
    private final UUID emp1Id = UUID.randomUUID();
    private final UUID emp2Id = UUID.randomUUID();
    private final UUID otherManagersEmployeeId = UUID.randomUUID();
    private final String managerEmail = "manager@test.com";

    private final LocalDate from = LocalDate.of(2026, 9, 1);
    private final LocalDate to = LocalDate.of(2026, 9, 16);

    private Employee manager;
    private Employee emp1;
    private Employee emp2;

    @BeforeEach
    void setUp() {
        manager = Employee.builder().userId(managerId).fullName("Manager One").build();
        emp1 = Employee.builder().userId(emp1Id).employeeCode("NF-1").fullName("Employee One").build();
        emp2 = Employee.builder().userId(emp2Id).employeeCode("NF-2").fullName("Employee Two").build();

        lenient().when(employeeRepository.findByUser_Email(managerEmail)).thenReturn(Optional.of(manager));
    }

    @Test
    void regularizationReport_onlyQueriesCallingManagersCurrentDirectReports_neverAnotherManagersTeam() {
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of(emp1Id, emp2Id));
        when(employeeRepository.findAllById(List.of(emp1Id, emp2Id))).thenReturn(List.of(emp1, emp2));
        when(regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(List.of(emp1Id, emp2Id), from, to))
                .thenReturn(List.of());

        reportsService.getAttendanceRequestReport(managerEmail, ReportsService.ReportType.REGULARIZATION, from, to);

        // The manager's own direct-report ids are the only ids ever handed to the data query —
        // resolved server-side from the authenticated principal, not from any client input.
        verify(regularizationRequestRepository).findByEmployeeUserIdInAndAttendanceDateBetween(List.of(emp1Id, emp2Id), from, to);
        verify(managerHistoryRepository, never()).findCurrentDirectReportIds(otherManagerId);
        verifyNoInteractions(webClockInRequestRepository);
    }

    @Test
    void managerWithNoDirectReports_returnsEmptyWithoutQueryingRequestData() {
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of());

        List<AttendanceRequestReportRow> rows =
                reportsService.getAttendanceRequestReport(managerEmail, ReportsService.ReportType.REGULARIZATION, from, to);

        assertTrue(rows.isEmpty());
        verifyNoInteractions(regularizationRequestRepository);
        verifyNoInteractions(webClockInRequestRepository);
    }

    @Test
    void regularizationReport_neverIncludesAnEmployeeOutsideTheResolvedReportIds() {
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of(emp1Id));
        when(employeeRepository.findAllById(List.of(emp1Id))).thenReturn(List.of(emp1));
        // Simulate a repository that (incorrectly) returned a row for an employee outside the
        // manager's own team; the service must not silently resolve/display it as if authorized.
        RegularizationRequest foreign = RegularizationRequest.builder()
                .employeeUserId(otherManagersEmployeeId).attendanceDate(from).status("PENDING")
                .requestedCheckIn(from.atTime(9, 0)).requestedCheckOut(from.atTime(18, 0)).build();
        when(regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(List.of(emp1Id), from, to))
                .thenReturn(List.of(foreign));

        List<AttendanceRequestReportRow> rows =
                reportsService.getAttendanceRequestReport(managerEmail, ReportsService.ReportType.REGULARIZATION, from, to);

        assertEquals(1, rows.size());
        assertNull(rows.get(0).getEmployeeCode(), "an id outside the manager's own team map has no name/code to leak");
        assertNull(rows.get(0).getFullName());
    }

    @Test
    void regularizationReport_mapsEmployeeDateCheckInOutAndStatus() {
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of(emp1Id));
        when(employeeRepository.findAllById(List.of(emp1Id))).thenReturn(List.of(emp1));
        LocalDateTime checkIn = LocalDateTime.of(2026, 9, 10, 9, 15);
        LocalDateTime checkOut = LocalDateTime.of(2026, 9, 10, 18, 30);
        RegularizationRequest req = RegularizationRequest.builder()
                .employeeUserId(emp1Id).attendanceDate(LocalDate.of(2026, 9, 10))
                .requestedCheckIn(checkIn).requestedCheckOut(checkOut)
                .reason("Forgot to punch").status("APPROVED").build();
        when(regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(List.of(emp1Id), from, to))
                .thenReturn(List.of(req));

        List<AttendanceRequestReportRow> rows =
                reportsService.getAttendanceRequestReport(managerEmail, ReportsService.ReportType.REGULARIZATION, from, to);

        assertEquals(1, rows.size());
        AttendanceRequestReportRow row = rows.get(0);
        assertEquals("NF-1", row.getEmployeeCode());
        assertEquals("Employee One", row.getFullName());
        assertEquals(LocalDate.of(2026, 9, 10), row.getDate());
        assertEquals(checkIn, row.getCheckIn());
        assertEquals(checkOut, row.getCheckOut());
        assertEquals("APPROVED", row.getStatus());
    }

    @Test
    void webClockInReport_statusReflectsCheckedInVsCheckedOut_notAReviewStatus() {
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of(emp1Id, emp2Id));
        when(employeeRepository.findAllById(List.of(emp1Id, emp2Id))).thenReturn(List.of(emp1, emp2));
        WebClockInRequest stillIn = WebClockInRequest.builder()
                .employeeUserId(emp1Id).workDate(LocalDate.of(2026, 9, 10))
                .requestedCheckIn(LocalDateTime.of(2026, 9, 10, 9, 0)).checkedOutAt(null).build();
        WebClockInRequest checkedOut = WebClockInRequest.builder()
                .employeeUserId(emp2Id).workDate(LocalDate.of(2026, 9, 10))
                .requestedCheckIn(LocalDateTime.of(2026, 9, 10, 9, 0))
                .checkedOutAt(LocalDateTime.of(2026, 9, 10, 18, 0)).build();
        when(webClockInRequestRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(emp1Id, emp2Id), from, to))
                .thenReturn(List.of(stillIn, checkedOut));

        List<AttendanceRequestReportRow> rows =
                reportsService.getAttendanceRequestReport(managerEmail, ReportsService.ReportType.WEB_CLOCK_IN, from, to);

        assertEquals(2, rows.size());
        var byEmployee = rows.stream().collect(java.util.stream.Collectors.toMap(AttendanceRequestReportRow::getEmployeeUserId, r -> r));
        assertEquals("Checked In", byEmployee.get(emp1Id).getStatus());
        assertEquals("Checked Out", byEmployee.get(emp2Id).getStatus());
        verifyNoInteractions(regularizationRequestRepository);
    }

    @Test
    void unknownManagerEmail_throwsRatherThanSilentlyReturningData() {
        when(employeeRepository.findByUser_Email("nobody@test.com")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                reportsService.getAttendanceRequestReport("nobody@test.com", ReportsService.ReportType.REGULARIZATION, from, to));
        verifyNoInteractions(regularizationRequestRepository);
        verifyNoInteractions(webClockInRequestRepository);
    }

    @Test
    void overtimeReport_mapsStartEndAndComputedHours_onlyQueriesCallingManagersTeam() {
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of(emp1Id));
        when(employeeRepository.findAllById(List.of(emp1Id))).thenReturn(List.of(emp1));
        LocalDateTime start = LocalDateTime.of(2026, 9, 10, 19, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 10, 20, 30);
        OvertimeRequest req = OvertimeRequest.builder()
                .employeeUserId(emp1Id).workDate(LocalDate.of(2026, 9, 10))
                .requestedStart(start).requestedEnd(end).reason("Release deployment").status("APPROVED").build();
        when(overtimeRequestRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(emp1Id), from, to))
                .thenReturn(List.of(req));

        List<AttendanceRequestReportRow> rows =
                reportsService.getAttendanceRequestReport(managerEmail, ReportsService.ReportType.OVERTIME, from, to);

        assertEquals(1, rows.size());
        AttendanceRequestReportRow row = rows.get(0);
        assertEquals("NF-1", row.getEmployeeCode());
        assertEquals(start, row.getCheckIn());
        assertEquals(end, row.getCheckOut());
        assertEquals(new BigDecimal("1.50"), row.getHours());
        assertEquals("APPROVED", row.getStatus());
        verify(overtimeRequestRepository).findByEmployeeUserIdInAndWorkDateBetween(List.of(emp1Id), from, to);
        verifyNoInteractions(attendanceRequestRepository, regularizationRequestRepository, webClockInRequestRepository);
    }

    @Test
    void partialDayReport_mapsModeAndHours_queriesOnlyPartialDayRequestType() {
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of(emp1Id));
        when(employeeRepository.findAllById(List.of(emp1Id))).thenReturn(List.of(emp1));
        AttendanceRequest req = AttendanceRequest.builder()
                .employeeUserId(emp1Id).requestType("PARTIAL_DAY").requestDate(LocalDate.of(2026, 9, 12))
                .partialDayMode("LATE_ARRIVE").partialDayHours(new BigDecimal("1.50"))
                .reason("Doctor appointment").status("PENDING").build();
        when(attendanceRequestRepository.findByEmployeeUserIdInAndRequestTypeAndRequestDateBetween(
                List.of(emp1Id), "PARTIAL_DAY", from, to)).thenReturn(List.of(req));

        List<AttendanceRequestReportRow> rows =
                reportsService.getAttendanceRequestReport(managerEmail, ReportsService.ReportType.PARTIAL_DAY, from, to);

        assertEquals(1, rows.size());
        AttendanceRequestReportRow row = rows.get(0);
        assertEquals("LATE_ARRIVE", row.getRequestMode());
        assertEquals(new BigDecimal("1.50"), row.getHours());
        assertNull(row.getCheckIn());
        verify(attendanceRequestRepository).findByEmployeeUserIdInAndRequestTypeAndRequestDateBetween(
                List.of(emp1Id), "PARTIAL_DAY", from, to);
    }

    @Test
    void wfhOdReport_mapsFullDayModeAndDayFraction_queriesOnlyWfhRequestType() {
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of(emp1Id));
        when(employeeRepository.findAllById(List.of(emp1Id))).thenReturn(List.of(emp1));
        AttendanceRequest req = AttendanceRequest.builder()
                .employeeUserId(emp1Id).requestType("WFH").requestDate(LocalDate.of(2026, 9, 13))
                .partialDayMode("FULL_DAY").wfhDayFraction(new BigDecimal("1.00"))
                .reason("Home network install").status("APPROVED").build();
        when(attendanceRequestRepository.findByEmployeeUserIdInAndRequestTypeAndRequestDateBetween(
                List.of(emp1Id), "WFH", from, to)).thenReturn(List.of(req));

        List<AttendanceRequestReportRow> rows =
                reportsService.getAttendanceRequestReport(managerEmail, ReportsService.ReportType.WFH_OD, from, to);

        assertEquals(1, rows.size());
        AttendanceRequestReportRow row = rows.get(0);
        assertEquals("FULL_DAY", row.getRequestMode());
        assertEquals(new BigDecimal("1.00"), row.getHours());
        verify(attendanceRequestRepository).findByEmployeeUserIdInAndRequestTypeAndRequestDateBetween(
                List.of(emp1Id), "WFH", from, to);
    }
}
