package com.nforce.onehr.service;

import com.nforce.onehr.dto.reports.AttendanceRequestReportRow;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.repository.AttendanceRequestRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.OvertimeRequestRepository;
import com.nforce.onehr.repository.RegularizationRequestRepository;
import com.nforce.onehr.repository.WebClockInRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Manager: Attendance Request Reports Library (ONEHR-109) — Regularization ("Remote Clock-ins"),
 * Web Clock-in, Overtime, Partial Day and WFH ("Working Remotely (WFH/OD)") report cards. Shift &
 * Weekly Off Requests and Mobile Location Punches have no backing entity anywhere in the
 * codebase and are intentionally NOT implemented here — the frontend renders them as disabled
 * "Coming soon" cards instead of silently returning empty data (ONEHR-109 AC #5). There is also
 * no "OD" (on-duty) request type anywhere in the schema — WFH_OD reports only WFH rows, each
 * carrying its FULL_DAY/FIRST_HALF/SECOND_HALF mode.
 */
@Service
@RequiredArgsConstructor
public class ReportsService {

    public enum ReportType { REGULARIZATION, WEB_CLOCK_IN, OVERTIME, PARTIAL_DAY, WFH_OD }

    private static final String ATTENDANCE_REQUEST_TYPE_PARTIAL_DAY = "PARTIAL_DAY";
    private static final String ATTENDANCE_REQUEST_TYPE_WFH = "WFH";

    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository managerHistoryRepository;
    private final RegularizationRequestRepository regularizationRequestRepository;
    private final WebClockInRequestRepository webClockInRequestRepository;
    private final OvertimeRequestRepository overtimeRequestRepository;
    private final AttendanceRequestRepository attendanceRequestRepository;

    @Transactional(readOnly = true)
    public List<AttendanceRequestReportRow> getAttendanceRequestReport(
            String managerEmail, ReportType type, LocalDate from, LocalDate to) {
        Employee manager = resolveManager(managerEmail);
        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        if (reportIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Employee> byId = employeeRepository.findAllById(reportIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Function.identity()));

        List<AttendanceRequestReportRow> rows = switch (type) {
            case REGULARIZATION -> regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(reportIds, from, to).stream()
                    .map(r -> AttendanceRequestReportRow.builder()
                            .employeeUserId(r.getEmployeeUserId())
                            .employeeCode(codeOf(byId, r.getEmployeeUserId()))
                            .fullName(nameOf(byId, r.getEmployeeUserId()))
                            .date(r.getAttendanceDate())
                            .checkIn(r.getRequestedCheckIn())
                            .checkOut(r.getRequestedCheckOut())
                            .reason(r.getReason())
                            .status(r.getStatus())
                            .build())
                    .toList();
            case WEB_CLOCK_IN -> webClockInRequestRepository.findByEmployeeUserIdInAndWorkDateBetween(reportIds, from, to).stream()
                    .map(r -> AttendanceRequestReportRow.builder()
                            .employeeUserId(r.getEmployeeUserId())
                            .employeeCode(codeOf(byId, r.getEmployeeUserId()))
                            .fullName(nameOf(byId, r.getEmployeeUserId()))
                            .date(r.getWorkDate())
                            .checkIn(r.getRequestedCheckIn())
                            .checkOut(r.getCheckedOutAt())
                            .reason(r.getReason())
                            // Web Clock-In has no review status at all (see
                            // WebClockInService's own class Javadoc) — this column instead
                            // reflects the session's own completion state.
                            .status(r.getCheckedOutAt() != null ? "Checked Out" : "Checked In")
                            .build())
                    .toList();
            case OVERTIME -> overtimeRequestRepository.findByEmployeeUserIdInAndWorkDateBetween(reportIds, from, to).stream()
                    .map(r -> AttendanceRequestReportRow.builder()
                            .employeeUserId(r.getEmployeeUserId())
                            .employeeCode(codeOf(byId, r.getEmployeeUserId()))
                            .fullName(nameOf(byId, r.getEmployeeUserId()))
                            .date(r.getWorkDate())
                            .checkIn(r.getRequestedStart())
                            .checkOut(r.getRequestedEnd())
                            .reason(r.getReason())
                            .status(r.getStatus())
                            .hours(hoursBetween(r.getRequestedStart(), r.getRequestedEnd()))
                            .build())
                    .toList();
            case PARTIAL_DAY -> attendanceRequestRepository.findByEmployeeUserIdInAndRequestTypeAndRequestDateBetween(
                            reportIds, ATTENDANCE_REQUEST_TYPE_PARTIAL_DAY, from, to).stream()
                    .map(r -> AttendanceRequestReportRow.builder()
                            .employeeUserId(r.getEmployeeUserId())
                            .employeeCode(codeOf(byId, r.getEmployeeUserId()))
                            .fullName(nameOf(byId, r.getEmployeeUserId()))
                            .date(r.getRequestDate())
                            .reason(r.getReason())
                            .status(r.getStatus())
                            .requestMode(r.getPartialDayMode())
                            .hours(r.getPartialDayHours())
                            .build())
                    .toList();
            case WFH_OD -> attendanceRequestRepository.findByEmployeeUserIdInAndRequestTypeAndRequestDateBetween(
                            reportIds, ATTENDANCE_REQUEST_TYPE_WFH, from, to).stream()
                    .map(r -> AttendanceRequestReportRow.builder()
                            .employeeUserId(r.getEmployeeUserId())
                            .employeeCode(codeOf(byId, r.getEmployeeUserId()))
                            .fullName(nameOf(byId, r.getEmployeeUserId()))
                            .date(r.getRequestDate())
                            .reason(r.getReason())
                            .status(r.getStatus())
                            .requestMode(r.getPartialDayMode())
                            .hours(r.getWfhDayFraction())
                            .build())
                    .toList();
        };

        return rows.stream()
                .sorted(Comparator.comparing(AttendanceRequestReportRow::getDate,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }

    public String toCsv(List<AttendanceRequestReportRow> rows) {
        StringBuilder sb = new StringBuilder("Employee Code,Employee Name,Date,Check In,Check Out,Mode,Hours,Reason,Status\n");
        for (AttendanceRequestReportRow r : rows) {
            sb.append(csvEscape(r.getEmployeeCode())).append(',')
              .append(csvEscape(r.getFullName())).append(',')
              .append(r.getDate() != null ? r.getDate() : "").append(',')
              .append(r.getCheckIn() != null ? r.getCheckIn() : "").append(',')
              .append(r.getCheckOut() != null ? r.getCheckOut() : "").append(',')
              .append(csvEscape(r.getRequestMode())).append(',')
              .append(r.getHours() != null ? r.getHours() : "").append(',')
              .append(csvEscape(r.getReason())).append(',')
              .append(csvEscape(r.getStatus())).append('\n');
        }
        return sb.toString();
    }

    /** Requested overtime duration in decimal hours (e.g. 1h30m -> 1.50), rounded to 2 places. */
    private BigDecimal hoursBetween(java.time.LocalDateTime start, java.time.LocalDateTime end) {
        if (start == null || end == null) {
            return null;
        }
        return BigDecimal.valueOf(Duration.between(start, end).toMinutes())
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    private String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) ? "\"" + escaped + "\"" : escaped;
    }

    private String codeOf(Map<UUID, Employee> byId, UUID employeeUserId) {
        Employee e = byId.get(employeeUserId);
        return e != null ? e.getEmployeeCode() : null;
    }

    private String nameOf(Map<UUID, Employee> byId, UUID employeeUserId) {
        Employee e = byId.get(employeeUserId);
        return e != null ? e.getFullName() : null;
    }

    private Employee resolveManager(String actorEmail) {
        return employeeRepository.findByUser_Email(actorEmail)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No employee profile found for this account. Contact HR to complete your profile."));
    }
}
