package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.dto.PunchResponse;
import com.nforce.onehr.dto.TodayAttendanceResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.AttendancePunch;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.repository.AttendancePunchRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    // Attendance policy: every 3rd late arrival in a calendar month costs a half-day, deducted
    // from Casual Leave — see applyLatePenaltyIfDue.
    private static final int LATE_PENALTY_EVERY_N = 3;
    private static final BigDecimal LATE_PENALTY_DAYS = new BigDecimal("0.5");
    private static final String LATE_PENALTY_LEAVE_TYPE_CODE = "CASUAL";

    private final AttendanceRepository attendanceRepository;
    private final AttendancePunchRepository attendancePunchRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository managerHistoryRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final AttendanceProperties props;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final NotificationService notificationService;

    // ---------------------------------------------------------------- self-service

    @Transactional(readOnly = true)
    public TodayAttendanceResponse getToday(String actorEmail) {
        Employee employee = resolveEmployee(actorEmail);
        LocalDateTime now = now();
        LocalDate today = now.toLocalDate();

        // A shift can cross midnight (e.g. 3:30 PM - 12:30 AM) — an open session started
        // yesterday is still the actionable "today" state even once the calendar date has
        // rolled over, so it takes priority over a plain work_date lookup.
        Optional<Attendance> open = attendanceRepository.findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc(employee.getUserId());
        if (open.isPresent()) {
            Attendance record = open.get();
            return TodayAttendanceResponse.builder()
                    .workDate(record.getWorkDate())
                    .serverNow(now)
                    .canCheckIn(false)
                    .canCheckOut(true)
                    .record(toResponse(record, employee))
                    .build();
        }

        return attendanceRepository.findByEmployeeUserIdAndWorkDate(employee.getUserId(), today)
                .map(record -> TodayAttendanceResponse.builder()
                        .workDate(today)
                        .serverNow(now)
                        // No open session (checked above), so this record is always closed —
                        // another session can still be started (e.g. after a lunch break).
                        .canCheckIn(true)
                        .canCheckOut(false)
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

        // A shift can cross midnight (e.g. 3:30 PM - 12:30 AM) — an open session from
        // yesterday's work_date must block a fresh check-in exactly like an open session
        // filed under today would, so check for one regardless of date first.
        if (attendanceRepository.findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc(employee.getUserId()).isPresent()) {
            throw new IllegalArgumentException("You have already checked in today");
        }

        Optional<Attendance> existing = attendanceRepository.findByEmployeeUserIdAndWorkDate(employee.getUserId(), today);

        if (existing.isPresent()) {
            // No open session (checked above), so this is always a closed record — resuming
            // after a break (e.g. lunch). The day's original check-in time, late status, and
            // worked-minutes-so-far all stay put; only a new session opens.
            Attendance record = existing.get();
            record.setSessionStartedAt(now);
            record.setCheckOutAt(null);
            Attendance saved = attendanceRepository.save(record);
            openPunch(saved.getId(), now);
            auditService.log(employee.getUserId(), "ATTENDANCE_CHECKED_IN", saved.getId());
            return toResponse(saved, employee);
        }

        // The grace deadline (shift start + grace) decides WHETHER a check-in counts as late at
        // all — past it by even one second is late, full stop, no forgiveness beyond the grace
        // window itself. isLate must NOT be derived from lateByMinutes: Duration.toMinutes()
        // truncates, so someone 30 seconds past the deadline would floor to 0 minutes and
        // wrongly read as on-time.
        // Once late, though, lateByMinutes is measured from the shift's actual start time, not
        // from the deadline — the grace period is forgiveness for whether you're penalized at
        // all, not a discount on how late you're reported as being. E.g. shift 3:30 PM, grace
        // 15 min: checking in at 4:39 PM is "late by 1h 9m" (from 3:30), not "late by 54m"
        // (from the 3:45 deadline).
        LocalTime deadline = props.getShiftStart().plusMinutes(props.getLateGraceMinutes());
        boolean isLate = now.toLocalTime().isAfter(deadline);
        int lateByMinutes = isLate
                ? (int) Math.ceil(Duration.between(props.getShiftStart(), now.toLocalTime()).getSeconds() / 60.0)
                : 0;

        Attendance record = attendanceRepository.save(Attendance.builder()
                .employeeUserId(employee.getUserId())
                .workDate(today)
                .checkInAt(now)
                .sessionStartedAt(now)
                .status(isLate ? STATUS_LATE : STATUS_PRESENT)
                .lateByMinutes(lateByMinutes)
                .build());
        openPunch(record.getId(), now);
        if (isLate) {
            applyLatePenaltyIfDue(employee, today);
        }

        auditService.log(employee.getUserId(), "ATTENDANCE_CHECKED_IN", record.getId());
        return toResponse(record, employee);
    }

    /**
     * Policy: every LATE_PENALTY_EVERY_N'th late arrival in a calendar month (3rd, 6th, 9th...)
     * costs LATE_PENALTY_DAYS, deducted from the employee's Casual Leave balance the moment it
     * happens. Only called for a genuine new late arrival — never for a lunch-break resume,
     * since lateness is a once-per-day fact tied to the day's first check-in.
     * A missing Casual Leave balance for the year is logged and skipped rather than thrown —
     * a leave-balance misconfiguration must never block someone from checking in.
     */
    private void applyLatePenaltyIfDue(Employee employee, LocalDate workDate) {
        LocalDate monthStart = workDate.withDayOfMonth(1);
        LocalDate monthEnd = workDate.withDayOfMonth(workDate.lengthOfMonth());
        long lateCountThisMonth = attendanceRepository.countByEmployeeUserIdAndWorkDateBetweenAndStatus(
                employee.getUserId(), monthStart, monthEnd, STATUS_LATE);
        if (lateCountThisMonth == 0 || lateCountThisMonth % LATE_PENALTY_EVERY_N != 0) {
            return;
        }

        Optional<LeaveType> leaveType = leaveTypeRepository.findByCode(LATE_PENALTY_LEAVE_TYPE_CODE);
        if (leaveType.isEmpty()) {
            log.warn("Late-arrival penalty skipped for employee {}: leave type {} not configured",
                    employee.getUserId(), LATE_PENALTY_LEAVE_TYPE_CODE);
            return;
        }
        Optional<LeaveBalance> balanceOpt = leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(
                employee.getUserId(), leaveType.get().getId(), workDate.getYear());
        if (balanceOpt.isEmpty()) {
            log.warn("Late-arrival penalty skipped for employee {}: no {} balance configured for {}",
                    employee.getUserId(), LATE_PENALTY_LEAVE_TYPE_CODE, workDate.getYear());
            return;
        }

        LeaveBalance balance = balanceOpt.get();
        String before = auditSnapshot.toJson(Map.of("usedDays", balance.getUsedDays()));
        balance.setUsedDays(balance.getUsedDays().add(LATE_PENALTY_DAYS));
        leaveBalanceRepository.save(balance);
        String after = auditSnapshot.toJson(Map.of("usedDays", balance.getUsedDays(), "lateCountThisMonth", lateCountThisMonth));
        auditService.log(employee.getUserId(), "LATE_ARRIVAL_PENALTY_APPLIED", balance.getId(), before, after);

        notificationService.send(employee.getUserId(), "ATTENDANCE",
                "Half-day deducted for late arrivals",
                "You've been late " + lateCountThisMonth + " times this month, so " + LATE_PENALTY_DAYS
                        + " day has been deducted from your Casual Leave balance.",
                "/attendance");
    }

    private void openPunch(UUID attendanceRecordId, LocalDateTime checkInAt) {
        attendancePunchRepository.save(AttendancePunch.builder()
                .attendanceRecordId(attendanceRecordId)
                .checkInAt(checkInAt)
                .build());
    }

    @Transactional
    public AttendanceResponse checkOut(String actorEmail) {
        Employee employee = resolveEmployee(actorEmail);

        LocalDateTime now = now();

        // Looked up by open session, not by today's work_date — a shift that started before
        // midnight (e.g. 3:30 PM - 12:30 AM) is still open under *yesterday's* work_date once
        // the calendar date rolls over.
        Attendance record = attendanceRepository
                .findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc(employee.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("You have not checked in today"));

        // Sessions accumulate: only this session's minutes are added to whatever was
        // already worked earlier today, so a lunch break isn't counted as worked time.
        LocalDateTime sessionStart = record.getSessionStartedAt() != null
                ? record.getSessionStartedAt() : record.getCheckInAt();
        int sessionMinutes = (int) Duration.between(sessionStart, now).toMinutes();
        int workedMinutes = (record.getWorkedMinutes() != null ? record.getWorkedMinutes() : 0) + sessionMinutes;
        String before = auditSnapshot.toJson(Map.of(
                "checkOutAt", "null", "workedMinutes", record.getWorkedMinutes() != null ? record.getWorkedMinutes() : 0));
        record.setCheckOutAt(now);
        record.setWorkedMinutes(workedMinutes);

        // A short day overrides LATE — the shortfall is the more significant fact for payroll.
        if (workedMinutes < props.getHalfDayMaxHours() * 60) {
            record.setStatus(STATUS_HALF_DAY);
        } else {
            record.setStatus(record.getLateByMinutes() > 0 ? STATUS_LATE : STATUS_PRESENT);
        }

        Attendance saved = attendanceRepository.save(record);
        attendancePunchRepository.findByAttendanceRecordIdAndCheckOutAtIsNull(saved.getId())
                .ifPresent(punch -> {
                    punch.setCheckOutAt(now);
                    attendancePunchRepository.save(punch);
                });
        String after = auditSnapshot.toJson(Map.of(
                "checkOutAt", now.toString(), "workedMinutes", workedMinutes, "status", saved.getStatus()));
        auditService.log(employee.getUserId(), "ATTENDANCE_CHECKED_OUT", saved.getId(), before, after);
        return toResponse(saved, employee);
    }

    /** Every check-in/check-out session for a single day — e.g. to show a lunch-break gap. */
    @Transactional(readOnly = true)
    public List<PunchResponse> getPunches(String actorEmail, LocalDate date) {
        Employee employee = resolveEmployee(actorEmail);
        return attendanceRepository.findByEmployeeUserIdAndWorkDate(employee.getUserId(), date)
                .map(record -> attendancePunchRepository.findByAttendanceRecordIdOrderByCheckInAtAsc(record.getId())
                        .stream()
                        .map(p -> PunchResponse.builder()
                                .id(p.getId())
                                .checkInAt(p.getCheckInAt())
                                .checkOutAt(p.getCheckOutAt())
                                .build())
                        .toList())
                .orElse(List.of());
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
     * Attendance rows for the caller's current direct reports across a date range — backs the
     * My Team calendar. Unlike {@link #getDayForMyTeam}, this returns only rows that actually
     * exist (no synthetic per-day placeholders for a whole team/range); the caller infers a
     * "missing attendance" day from the absence of a row on a working day.
     */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> getMonthForMyTeam(String managerEmail, LocalDate from, LocalDate to) {
        Employee manager = resolveEmployee(managerEmail);
        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        if (reportIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Employee> byId = employeeRepository.findAllById(reportIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Function.identity()));
        return attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(reportIds, from, to).stream()
                .map(r -> toResponse(r, byId.get(r.getEmployeeUserId())))
                .toList();
    }

    /**
     * Day roster limited to the caller's current peers (same-manager siblings) — backs the
     * employee-facing My Team: Peers view (ONEHR-73). Mirrors {@link #getDayForMyTeam} exactly,
     * swapping direct-report resolution for peer resolution.
     */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> getDayForPeers(String employeeEmail, LocalDate date) {
        LocalDate day = date != null ? date : now().toLocalDate();
        Employee self = resolveEmployee(employeeEmail);

        List<UUID> peerIds = managerHistoryRepository.findCurrentPeerIds(self.getUserId());
        if (peerIds.isEmpty()) {
            return List.of();
        }

        List<Employee> peers = employeeRepository.findAllById(peerIds).stream()
                .filter(e -> e.getUser() != null && e.getUser().getDeletedAt() == null)
                .toList();
        List<Attendance> records =
                attendanceRepository.findByWorkDateAndEmployeeUserIdIn(day, peerIds);
        return joinRoster(peers, records, day);
    }

    /** Peer attendance across a date range — backs the Peers view calendar. Mirrors {@link #getMonthForMyTeam}. */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> getMonthForPeers(String employeeEmail, LocalDate from, LocalDate to) {
        Employee self = resolveEmployee(employeeEmail);
        List<UUID> peerIds = managerHistoryRepository.findCurrentPeerIds(self.getUserId());
        if (peerIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Employee> byId = employeeRepository.findAllById(peerIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Function.identity()));
        return attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(peerIds, from, to).stream()
                .map(r -> toResponse(r, byId.get(r.getEmployeeUserId())))
                .toList();
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
                            .workMode(employee.getWorkMode())
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
                .sessionStartedAt(record.getSessionStartedAt())
                .workedMinutes(worked)
                .status(record.getStatus())
                .lateByMinutes(record.getLateByMinutes())
                .fullDay(worked == null ? null : worked >= props.getFullDayMinHours() * 60)
                .source(record.getSource())
                .workMode(employee.getWorkMode())
                .build();
    }
}
