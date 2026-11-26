package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.attendance.MyPenaltiesResponse;
import com.nforce.onehr.dto.attendance.MyPenaltyResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.AttendanceException;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.AttendancePenaltyStatus;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.ExceptionType;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.entity.RegularizationRequest;
import com.nforce.onehr.repository.AttendanceExceptionRepository;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.RegularizationRequestRepository;
import com.nforce.onehr.repository.ShiftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** PN-01: the employee's own My Penalties view. Pure Mockito — no DB. */
@ExtendWith(MockitoExtension.class)
class MyPenaltyServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);
    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = LocalDate.of(2026, 11, 30);

    @Mock private AttendancePenaltyRepository attendancePenaltyRepository;
    @Mock private AttendanceExceptionRepository attendanceExceptionRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private RegularizationRequestRepository regularizationRequestRepository;
    @Mock private LeaveTypeRepository leaveTypeRepository;
    @Mock private ShiftRepository shiftRepository;
    @Mock private ShiftVersionResolver shiftVersionResolver;
    @Mock private PenalizationPolicyResolutionService policyResolutionService;
    @Mock private AttendanceProperties attendanceProperties;
    @Mock private PenaltyDisputeService penaltyDisputeService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private MyPenaltyService service;

    private final UUID userId = UUID.randomUUID();
    private Employee employee;
    private PenalizationPolicyVersion policy;

    @BeforeEach
    void setUp() {
        employee = Employee.builder().userId(userId).fullName("Emp One").employeeCode("NF-1").build();
        lenient().when(employeeRepository.findByUser_Email("emp@x.com")).thenReturn(Optional.of(employee));
        policy = PenalizationPolicyVersion.builder()
                .lateArrivalEnabled(true).laExemptCount(3).laExemptPeriod("MONTH")
                .missingLogsEnabled(true).workHoursShortageEnabled(true)
                .bufferPeriodDays(3).build();
        lenient().when(policyResolutionService.resolveEffectiveVersionForEmployee(eq(employee), any())).thenReturn(policy);
        lenient().when(attendancePenaltyRepository.findByEmployeeUserIdAndIncidentDateBetween(eq(userId), any(), any())).thenReturn(List.of());
        lenient().when(attendanceExceptionRepository.findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(any(), any(), any())).thenReturn(List.of());
        lenient().when(attendanceRepository.findByEmployeeUserIdAndWorkDateBetweenOrderByWorkDateDesc(eq(userId), any(), any())).thenReturn(List.of());
        lenient().when(regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(any(), any(), any())).thenReturn(List.of());
        lenient().when(attendanceRepository.countByEmployeeUserIdAndWorkDateBetweenAndStatus(eq(userId), any(), any(), eq("LATE"))).thenReturn(3L);
    }

    @Test
    void appliedPenaltyShowsRuleDetailsDeductionAndLeaveLopSplit() {
        LocalDate incident = LocalDate.of(2026, 11, 6);
        AttendancePenalty p = AttendancePenalty.builder().id(UUID.randomUUID()).employeeUserId(userId).incidentDate(incident)
                .discrepancyType(ExceptionType.LATE_ARRIVAL).status(AttendancePenaltyStatus.PENDING_REVIEW)
                .deductionDays(new BigDecimal("1")).leaveDeductionDays(new BigDecimal("0.5")).lopDays(new BigDecimal("0.5"))
                .leaveBreakdown("{\"CASUAL\":0.5}").penalizedOn(LocalDateTime.of(2026, 11, 10, 0, 15)).build();
        when(attendancePenaltyRepository.findByEmployeeUserIdAndIncidentDateBetween(eq(userId), any(), any())).thenReturn(List.of(p));
        AttendanceException incidentRow = AttendanceException.builder().employeeUserId(userId).exceptionDate(incident)
                .exceptionType(ExceptionType.LATE_ARRIVAL).expectedTime(LocalTime.of(10, 0)).actualTime(LocalTime.of(10, 42)).minutesLate(42).build();
        when(attendanceExceptionRepository.findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(any(), any(), any()))
                .thenReturn(List.of(incidentRow));
        LeaveType casual = new LeaveType();
        casual.setCode("CASUAL");
        casual.setName("Casual Leave");
        when(leaveTypeRepository.findByCode("CASUAL")).thenReturn(Optional.of(casual));

        MyPenaltiesResponse res = service.getMine("emp@x.com", FROM, TO, TODAY);

        assertEquals(1, res.getRows().size());
        MyPenaltyResponse row = res.getRows().get(0);
        assertEquals("Late Arrival", row.getRule());
        assertEquals("Arrived 10:42, shift starts 10:00", row.getDetails()); // no shift snapshot → no grace figure
        assertEquals(0, new BigDecimal("1").compareTo(row.getDeductionDays()));
        assertEquals(0, new BigDecimal("0.5").compareTo(row.getLopDays()));
        assertEquals("Casual Leave", row.getLeaveBreakdown().get(0).getLeaveTypeName());
        assertEquals(0, new BigDecimal("0.5").compareTo(row.getLeaveBreakdown().get(0).getDays()));
        assertFalse(row.isCorrectable(), "the buffer has elapsed by the time a penalty exists");
        // Summary: only active penalties are totalled
        assertEquals(1, res.getSummary().getActivePenalties());
        assertEquals(0, new BigDecimal("0.5").compareTo(res.getSummary().getLopDays()));
    }

    @Test
    void ruleLabelsMatchTheStoryNames() {
        assertEquals("Late Arrival", MyPenaltyService.ruleLabel(ExceptionType.LATE_ARRIVAL));
        assertEquals("Shortage", MyPenaltyService.ruleLabel(ExceptionType.WORK_HOURS_SHORTAGE));
        assertEquals("No Attendance", MyPenaltyService.ruleLabel(ExceptionType.NO_ATTENDANCE));
        assertEquals("Missing Logs", MyPenaltyService.ruleLabel(ExceptionType.MISSING_PUNCH));
    }

    @Test
    void lateMarksTileReportsUsedAgainstExemptCount() {
        MyPenaltiesResponse.Summary s = service.getMine("emp@x.com", FROM, TO, TODAY).getSummary();
        assertTrue(s.isLateRuleEnabled());
        assertEquals(3, s.getLateMarks());
        assertEquals(3, s.getLateMarksExempt());
        assertEquals("MONTH", s.getPeriod());
        assertEquals(LocalDate.of(2026, 11, 1), s.getPeriodFrom());
    }

    @Test
    void incidentInsideBufferIsListedWithCorrectiveActionsUntilBufferEnds() {
        // 3 late marks this month vs exempt 3 → the 3rd is still exempt; make it the 4th so it can be penalized.
        when(attendanceRepository.countByEmployeeUserIdAndWorkDateBetweenAndStatus(eq(userId), any(), any(), eq("LATE"))).thenReturn(4L);
        LocalDate yesterday = TODAY.minusDays(1);
        AttendanceException late = AttendanceException.builder().id(UUID.randomUUID()).employeeUserId(userId).exceptionDate(yesterday)
                .exceptionType(ExceptionType.LATE_ARRIVAL).expectedTime(LocalTime.of(10, 0)).actualTime(LocalTime.of(10, 42)).build();
        // Outside the 3-day buffer (incident 5 days ago) — must not appear as in-buffer.
        AttendanceException old = AttendanceException.builder().id(UUID.randomUUID()).employeeUserId(userId).exceptionDate(TODAY.minusDays(5))
                .exceptionType(ExceptionType.MISSING_PUNCH).build();
        when(attendanceExceptionRepository.findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(any(), any(), any()))
                .thenReturn(List.of(late, old));

        List<MyPenaltyResponse> rows = service.getMine("emp@x.com", FROM, TO, TODAY).getRows();

        assertEquals(1, rows.size());
        MyPenaltyResponse row = rows.get(0);
        assertEquals(MyPenaltyService.IN_BUFFER, row.getStatus());
        assertTrue(row.isCorrectable());
        assertEquals(yesterday.plusDays(2), row.getCorrectableUntil()); // 3-day buffer, inclusive
        assertNull(row.getDeductionDays());
    }

    @Test
    void exemptLateMarkIsNotShownAsAtRisk() {
        // Only 2 late marks so far this month, exempt count 3 → this one will be exempt.
        when(attendanceRepository.countByEmployeeUserIdAndWorkDateBetweenAndStatus(eq(userId), any(), any(), eq("LATE"))).thenReturn(2L);
        AttendanceException late = AttendanceException.builder().id(UUID.randomUUID()).employeeUserId(userId).exceptionDate(TODAY.minusDays(1))
                .exceptionType(ExceptionType.LATE_ARRIVAL).build();
        when(attendanceExceptionRepository.findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(any(), any(), any()))
                .thenReturn(List.of(late));

        assertTrue(service.getMine("emp@x.com", FROM, TO, TODAY).getRows().isEmpty());
    }

    @Test
    void openRegularizationHidesCorrectiveActions() {
        when(attendanceRepository.countByEmployeeUserIdAndWorkDateBetweenAndStatus(eq(userId), any(), any(), eq("LATE"))).thenReturn(5L);
        LocalDate d = TODAY.minusDays(1);
        AttendanceException late = AttendanceException.builder().id(UUID.randomUUID()).employeeUserId(userId).exceptionDate(d)
                .exceptionType(ExceptionType.LATE_ARRIVAL).build();
        when(attendanceExceptionRepository.findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(any(), any(), any()))
                .thenReturn(List.of(late));
        RegularizationRequest reg = new RegularizationRequest();
        reg.setAttendanceDate(d);
        reg.setStatus("PENDING");
        when(regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(any(), any(), any())).thenReturn(List.of(reg));

        MyPenaltyResponse row = service.getMine("emp@x.com", FROM, TO, TODAY).getRows().get(0);
        assertFalse(row.isCorrectable());
        assertTrue(row.isRegularizationOpen());
    }

    @Test
    void shortageDetailsUseWorkedMinutes() {
        LocalDate d = LocalDate.of(2026, 11, 3);
        AttendancePenalty p = AttendancePenalty.builder().id(UUID.randomUUID()).employeeUserId(userId).incidentDate(d)
                .discrepancyType(ExceptionType.WORK_HOURS_SHORTAGE).status(AttendancePenaltyStatus.CANCELLED)
                .deductionDays(new BigDecimal("0.5")).cancellationReason("ok").build();
        when(attendancePenaltyRepository.findByEmployeeUserIdAndIncidentDateBetween(eq(userId), any(), any())).thenReturn(List.of(p));
        Attendance att = Attendance.builder().employeeUserId(userId).workDate(d).workedMinutes(310).build();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDateBetweenOrderByWorkDateDesc(eq(userId), any(), any())).thenReturn(List.of(att));

        MyPenaltiesResponse res = service.getMine("emp@x.com", FROM, TO, TODAY);
        assertEquals("Worked 5h 10m, below the hours required for the day", res.getRows().get(0).getDetails());
        assertEquals(0, res.getSummary().getActivePenalties(), "cancelled penalties are not part of the totals");
    }

    @Test
    void invalidRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.getMine("emp@x.com", TO, FROM, TODAY));
    }
}
