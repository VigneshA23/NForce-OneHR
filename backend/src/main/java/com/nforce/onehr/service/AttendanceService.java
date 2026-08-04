package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.dto.TodayAttendanceResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Punch-clock attendance: self-service check-in/check-out plus HR/Manager roster views.
 * Regularization (employee-submitted corrections) lives in {@link RegularizationService},
 * which upserts into the same {@link Attendance} rows this service writes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {

    private static final String STATUS_PRESENT = "PRESENT";
    private static final String STATUS_LATE = "LATE";
    private static final String STATUS_HALF_DAY = "HALF_DAY";

    private static final int DEFAULT_HISTORY_DAYS = 30;

    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository managerHistoryRepository;
    private final AuditService auditService;
    private final AttendanceProperties props;

    // ---------------------------------------------------------------- self-service

    @Transactional(readOnly = true)
    public TodayAttendanceResponse getToday(String actorEmail) {
        Employee employee = resolveEmployee(actorEmail);
        LocalDateTime now = now();
        LocalDate today = now.toLocalDate();

        return attendanceRepository.findByEmployeeUserIdAndWorkDate(employee.getUserId(), today)
                .map(record -> TodayAttendanceResponse.builder()
                        .workDate(today)
                        .serverNow(now)
                        // A day may have several check-in/check-out sessions (e.g. a lunch
                        // break) — checkOutAt null means a session is currently open.
                        .canCheckIn(record.getCheckOutAt() != null)
                        .canCheckOut(record.getCheckOutAt() == null)
                        .record(toResponse(record, employee))
                        .build())
                .orElseGet(() -> TodayAttendanceResponse.builder()
                        .workDate(today)
                        .serverNow(now)
                        .canCheckIn(true)
                        .canCheckOut(false)
                        .record(null)
                        .build());
    }

    @Transactional
    public AttendanceResponse checkIn(String actorEmail) {
        Employee employee = resolveEmployee(actorEmail);

        // Single clock read: the punch time and the work date it is attributed to can never disagree.
        LocalDateTime now = now();
        LocalDate today = now.toLocalDate();

        Optional<Attendance> existing = attendanceRepository.findByEmployeeUserIdAndWorkDate(employee.getUserId(), today);

        if (existing.isPresent()) {
            Attendance record = existing.get();
            if (record.getCheckOutAt() == null) {
                throw new IllegalArgumentException("You have already checked in today");
            }
            // Resuming after a break (e.g. lunch) — the day's original check-in time, late
            // status, and worked-minutes-so-far all stay put; only a new session opens.
            record.setSessionStartedAt(now);
            record.setCheckOutAt(null);
            Attendance saved = attendanceRepository.save(record);
            auditService.log(employee.getUserId(), "ATTENDANCE_CHECKED_IN", saved.getId());
            return toResponse(saved, employee);
        }

        // Minutes past the grace deadline (shift start + grace), not past shift start itself.
        LocalTime deadline = props.getShiftStart().plusMinutes(props.getLateGraceMinutes());
        int lateByMinutes = now.toLocalTime().isAfter(deadline)
                ? (int) Duration.between(deadline, now.toLocalTime()).toMinutes()
                : 0;

        Attendance record = attendanceRepository.save(Attendance.builder()
                .employeeUserId(employee.getUserId())
                .workDate(today)
                .checkInAt(now)
                .sessionStartedAt(now)
                .status(lateByMinutes > 0 ? STATUS_LATE : STATUS_PRESENT)
                .lateByMinutes(lateByMinutes)
                .build());

        auditService.log(employee.getUserId(), "ATTENDANCE_CHECKED_IN", record.getId());
        return toResponse(record, employee);
    }

    @Transactional
    public AttendanceResponse checkOut(String actorEmail) {
        Employee employee = resolveEmployee(actorEmail);

        LocalDateTime now = now();
        LocalDate today = now.toLocalDate();

        Attendance record = attendanceRepository
                .findByEmployeeUserIdAndWorkDate(employee.getUserId(), today)
                .orElseThrow(() -> new IllegalArgumentException("You have not checked in today"));

        if (record.getCheckOutAt() != null) {
            throw new IllegalArgumentException("You have already checked out today");
        }

        // Sessions accumulate: only this session's minutes are added to whatever was
        // already worked earlier today, so a lunch break isn't counted as worked time.
        LocalDateTime sessionStart = record.getSessionStartedAt() != null
                ? record.getSessionStartedAt() : record.getCheckInAt();
        int sessionMinutes = (int) Duration.between(sessionStart, now).toMinutes();
        int workedMinutes = (record.getWorkedMinutes() != null ? record.getWorkedMinutes() : 0) + sessionMinutes;
        record.setCheckOutAt(now);
        record.setWorkedMinutes(workedMinutes);

        // A short day overrides LATE — the shortfall is the more significant fact for payroll.
        if (workedMinutes < props.getHalfDayMaxHours() * 60) {
            record.setStatus(STATUS_HALF_DAY);
        } else {
            record.setStatus(record.getLateByMinutes() > 0 ? STATUS_LATE : STATUS_PRESENT);
        }

        Attendance saved = attendanceRepository.save(record);
        auditService.log(employee.getUserId(), "ATTENDANCE_CHECKED_OUT", saved.getId());
        return toResponse(saved, employee);
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponse> getMyHistory(String actorEmail, LocalDate from, LocalDate to) {
        Employee employee = resolveEmployee(actorEmail);
        return historyFor(employee, from, to);
    }

    /**
     * The caller's own punch for a single date, if any — backs the regularization request
     * form's auto-fill (Attendance Regularization spec scenarios 1/2: prefill whichever side
     * of the punch already exists so only the missing one needs to be entered). Null if the
     * employee never punched that day.
     */
    @Transactional(readOnly = true)
    public AttendanceResponse getPunchForDate(String actorEmail, LocalDate date) {
        Employee employee = resolveEmployee(actorEmail);
        return attendanceRepository.findByEmployeeUserIdAndWorkDate(employee.getUserId(), date)
                .map(record -> toResponse(record, employee))
                .orElse(null);
    }

    // ---------------------------------------------------------------- HR / Manager views

    /** Full day roster for HR — one row per active employee, punched or not. */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> getDayForAll(LocalDate date) {
        LocalDate day = date != null ? date : now().toLocalDate();
        List<Employee> employees = employeeRepository.findAllWithDetails();
        return joinRoster(employees, attendanceRepository.findByWorkDate(day), day);
    }

    /** Day roster limited to the caller's current direct reports. */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> getDayForMyTeam(String managerEmail, LocalDate date) {
        LocalDate day = date != null ? date : now().toLocalDate();
        Employee manager = resolveEmployee(managerEmail);

        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        if (reportIds.isEmpty()) {
            return List.of();
        }

        List<Employee> reports = employeeRepository.findAllById(reportIds).stream()
                .filter(e -> e.getUser() != null && e.getUser().getDeletedAt() == null)
                .toList();
        List<Attendance> records =
                attendanceRepository.findByWorkDateAndEmployeeUserIdIn(day, reportIds);
        return joinRoster(reports, records, day);
    }

    /**
     * Drill-down history for a single employee. HR and Super Admin may view anyone; a Manager
     * may only view their own current direct reports.
     */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> getEmployeeHistory(UUID employeeUserId, LocalDate from, LocalDate to,
                                                       String actorEmail, boolean restrictToDirectReports) {
        if (restrictToDirectReports) {
            Employee manager = resolveEmployee(actorEmail);
            if (!managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId())
                    .contains(employeeUserId)) {
                throw new AccessDeniedException("You can only view attendance for your direct reports");
            }
        }

        Employee employee = employeeRepository.findById(employeeUserId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));
        return historyFor(employee, from, to);
    }

    // ---------------------------------------------------------------- internals

    /**
     * Clock for every timestamp and work-date decision in this service. Reads the configured
     * business zone rather than the JVM default so "today" is identical in local dev and on
     * Railway (which runs UTC).
     */
    private LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of(props.getZone()));
    }

    private Employee resolveEmployee(String actorEmail) {
        return employeeRepository.findByUser_Email(actorEmail)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No employee profile found for this account. Contact HR to complete your profile."));
    }

    private List<AttendanceResponse> historyFor(Employee employee, LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : now().toLocalDate();
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_HISTORY_DAYS);
        return attendanceRepository
                .findByEmployeeUserIdAndWorkDateBetweenOrderByWorkDateDesc(
                        employee.getUserId(), start, end)
                .stream()
                .map(record -> toResponse(record, employee))
                .toList();
    }

    /** Left-joins a day's records onto an employee list so non-punchers still appear as a row. */
    private List<AttendanceResponse> joinRoster(List<Employee> employees, List<Attendance> records,
                                                LocalDate day) {
        Map<UUID, Attendance> byEmployee = records.stream()
                .collect(Collectors.toMap(Attendance::getEmployeeUserId, Function.identity()));

        List<AttendanceResponse> rows = new ArrayList<>(employees.size());
        for (Employee employee : employees) {
            Attendance record = byEmployee.get(employee.getUserId());
            rows.add(record != null
                    ? toResponse(record, employee)
                    : AttendanceResponse.builder()
                            .employeeUserId(employee.getUserId())
                            .employeeCode(employee.getEmployeeCode())
                            .fullName(employee.getFullName())
                            .workDate(day)
                            .build());
        }
        rows.sort(Comparator.comparing(AttendanceResponse::getFullName,
                Comparator.nullsLast(String::compareToIgnoreCase)));
        return rows;
    }

    private AttendanceResponse toResponse(Attendance record, Employee employee) {
        Integer worked = record.getWorkedMinutes();
        return AttendanceResponse.builder()
                .id(record.getId())
                .employeeUserId(record.getEmployeeUserId())
                .employeeCode(employee.getEmployeeCode())
                .fullName(employee.getFullName())
                .workDate(record.getWorkDate())
                .checkInAt(record.getCheckInAt())
                .checkOutAt(record.getCheckOutAt())
                .workedMinutes(worked)
                .status(record.getStatus())
                .lateByMinutes(record.getLateByMinutes())
                .fullDay(worked == null ? null : worked >= props.getFullDayMinHours() * 60)
                .source(record.getSource())
                .build();
    }
}
