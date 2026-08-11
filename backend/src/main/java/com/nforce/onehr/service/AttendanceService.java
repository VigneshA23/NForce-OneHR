package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.dto.PunchResponse;
import com.nforce.onehr.dto.TodayAttendanceResponse;
import com.nforce.onehr.dto.attendance.AttendanceConfigResponse;
import com.nforce.onehr.dto.attendance.AttendanceExceptionResponse;
import com.nforce.onehr.dto.attendance.TeamEffortEntry;
import com.nforce.onehr.dto.attendance.TeamNegligenceResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.AttendancePunch;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.WeeklyOffPolicy;
import com.nforce.onehr.repository.AttendanceExceptionRepository;
import com.nforce.onehr.repository.AttendancePunchRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
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
    private final AttendancePunchRepository attendancePunchRepository;
    private final AttendanceExceptionRepository attendanceExceptionRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository managerHistoryRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
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
                        .breakUsedMinutes(computeBreakMinutes(record.getId()))
                        .breakBudgetMinutes(props.getDailyBreakBudgetMinutes())
                        .build())
                .orElseGet(() -> TodayAttendanceResponse.builder()
                        .workDate(today)
                        .serverNow(now)
                        .canCheckIn(true)
                        .canCheckOut(false)
                        .record(null)
                        .breakUsedMinutes(null)
                        .breakBudgetMinutes(props.getDailyBreakBudgetMinutes())
                        .build());
    }

    /** Sum of the gaps between consecutive closed punch sessions — an open (unclosed) session contributes nothing yet. */
    private int computeBreakMinutes(UUID attendanceRecordId) {
        List<AttendancePunch> punches = attendancePunchRepository
                .findByAttendanceRecordIdOrderByCheckInAtAsc(attendanceRecordId);
        int breakMinutes = 0;
        for (int i = 0; i < punches.size() - 1; i++) {
            LocalDateTime gapStart = punches.get(i).getCheckOutAt();
            LocalDateTime gapEnd = punches.get(i + 1).getCheckInAt();
            if (gapStart != null && gapEnd != null) {
                breakMinutes += (int) Duration.between(gapStart, gapEnd).toMinutes();
            }
        }
        return breakMinutes;
    }

    /** Read-only mirror of AttendanceProperties for the Today's Timings panel — no shiftEnd exists (ONEHR-108 not built). */
    @Transactional(readOnly = true)
    public AttendanceConfigResponse getConfig(String actorEmail) {
        Employee employee = resolveEmployee(actorEmail);
        Shift shift = employee.getShift();
        WeeklyOffPolicy weeklyOffPolicy = employee.getWeeklyOffPolicy();

        return AttendanceConfigResponse.builder()
                .shiftStart(shift != null ? shift.getStartTime() : props.getShiftStart())
                .shiftEnd(shift != null ? shift.getEndTime() : null)
                .lateGraceMinutes(props.getLateGraceMinutes())
                .halfDayMaxHours(props.getHalfDayMaxHours())
                .fullDayMinHours(props.getFullDayMinHours())
                .dailyBreakBudgetMinutes(props.getDailyBreakBudgetMinutes())
                .weeklyOffDays(weeklyOffPolicy != null
                        ? Arrays.stream(weeklyOffPolicy.getOffDays().split(",")).map(String::trim).toList()
                        : List.of("SATURDAY", "SUNDAY"))
                .build();
    }

    /**
     * Always empty today — see AttendanceExceptionResponse's Javadoc. Passive read only, no
     * detection logic added here.
     */
    @Transactional(readOnly = true)
    public List<AttendanceExceptionResponse> getMyExceptions(String actorEmail, LocalDate from, LocalDate to) {
        Employee employee = resolveEmployee(actorEmail);
        return attendanceExceptionRepository
                .findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(
                        List.of(employee.getUserId()), from, to)
                .stream()
                .map(e -> AttendanceExceptionResponse.builder()
                        .id(e.getId())
                        .exceptionDate(e.getExceptionDate())
                        .exceptionType(e.getExceptionType())
                        .status(e.getStatus())
                        .minutesLate(e.getMinutesLate())
                        .build())
                .toList();
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
            openPunch(saved.getId(), now);
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
        openPunch(record.getId(), now);

        auditService.log(employee.getUserId(), "ATTENDANCE_CHECKED_IN", record.getId());
        return toResponse(record, employee);
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

    // ---------------------------------------------------------------- Team Effort / Negligence (ONEHR-106/107)

    private static final int EXPECTED_HOURS_PER_WORKDAY = 8;

    /** Avg. Work Hours Leaderboard — ranked desc by avg hrs/day over the range (ONEHR-106). */
    @Transactional(readOnly = true)
    public List<TeamEffortEntry> getTeamEffort(String managerEmail, LocalDate from, LocalDate to) {
        Employee manager = resolveEmployee(managerEmail);
        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        if (reportIds.isEmpty()) {
            return List.of();
        }

        List<Attendance> records = attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(reportIds, from, to);
        Map<UUID, Employee> byId = employeeRepository.findAllById(reportIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Function.identity()));
        double expectedHours = countWeekdays(from, to) * EXPECTED_HOURS_PER_WORKDAY;

        return groupByEmployee(records).entrySet().stream()
                .filter(e -> e.getValue().activeDays > 0)
                .map(e -> {
                    TeamStat stat = e.getValue();
                    Employee employee = byId.get(e.getKey());
                    return TeamEffortEntry.builder()
                            .employeeUserId(e.getKey())
                            .fullName(employee != null ? employee.getFullName() : null)
                            .designationName(designationOf(employee))
                            .avgHoursPerDay(round1(stat.totalWorkedMinutes / 60.0 / stat.activeDays))
                            .hoursWorked(round1(stat.totalWorkedMinutes / 60.0))
                            .expectedHours(expectedHours)
                            .activeDays(stat.activeDays)
                            .build();
                })
                .sorted(Comparator.comparingDouble(TeamEffortEntry::getAvgHoursPerDay).reversed())
                .toList();
    }

    /** The three Negligence panels: Late Arrivals, Least Hours Worked, Frequent Breaks (ONEHR-107). */
    @Transactional(readOnly = true)
    public TeamNegligenceResponse getTeamNegligence(String managerEmail, LocalDate from, LocalDate to) {
        Employee manager = resolveEmployee(managerEmail);
        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        if (reportIds.isEmpty()) {
            return TeamNegligenceResponse.builder()
                    .lateArrivals(List.of()).dailyLateCounts(List.of())
                    .leastHoursWorked(List.of()).hoursHistogram(List.of())
                    .frequentBreaks(List.of()).build();
        }

        List<Attendance> records = attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(reportIds, from, to);
        Map<UUID, Employee> byId = employeeRepository.findAllById(reportIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Function.identity()));
        Map<UUID, TeamStat> statsByEmployee = groupByEmployee(records);

        List<TeamNegligenceResponse.LateArrivalEntry> lateArrivals = statsByEmployee.entrySet().stream()
                .filter(e -> e.getValue().activeDays > 0)
                .map(e -> {
                    TeamStat stat = e.getValue();
                    Employee employee = byId.get(e.getKey());
                    return TeamNegligenceResponse.LateArrivalEntry.builder()
                            .employeeUserId(e.getKey())
                            .fullName(employee != null ? employee.getFullName() : null)
                            .designationName(designationOf(employee))
                            .lateDays(stat.lateDays)
                            .activeDays(stat.activeDays)
                            .latePct(round1(stat.lateDays * 100.0 / stat.activeDays))
                            .build();
                })
                .sorted(Comparator.comparingDouble(TeamNegligenceResponse.LateArrivalEntry::getLatePct).reversed())
                .toList();

        Map<LocalDate, Long> lateByDate = records.stream()
                .filter(r -> STATUS_LATE.equals(r.getStatus()))
                .collect(Collectors.groupingBy(Attendance::getWorkDate, Collectors.counting()));
        List<TeamNegligenceResponse.DailyCount> dailyLateCounts = from.datesUntil(to.plusDays(1))
                .map(d -> TeamNegligenceResponse.DailyCount.builder().date(d).count(lateByDate.getOrDefault(d, 0L)).build())
                .toList();

        List<TeamNegligenceResponse.LeastHoursEntry> leastHoursWorked = statsByEmployee.entrySet().stream()
                .filter(e -> e.getValue().activeDays > 0)
                .map(e -> {
                    TeamStat stat = e.getValue();
                    Employee employee = byId.get(e.getKey());
                    return TeamNegligenceResponse.LeastHoursEntry.builder()
                            .employeeUserId(e.getKey())
                            .fullName(employee != null ? employee.getFullName() : null)
                            .designationName(designationOf(employee))
                            .avgHoursPerDay(round1(stat.totalWorkedMinutes / 60.0 / stat.activeDays))
                            .hoursWorked(round1(stat.totalWorkedMinutes / 60.0))
                            .build();
                })
                .sorted(Comparator.comparingDouble(TeamNegligenceResponse.LeastHoursEntry::getAvgHoursPerDay))
                .toList();

        FrequentBreaksResult breaks = computeFrequentBreaks(records, byId, from, to);

        return TeamNegligenceResponse.builder()
                .lateArrivals(lateArrivals)
                .dailyLateCounts(dailyLateCounts)
                .leastHoursWorked(leastHoursWorked)
                .hoursHistogram(buildHoursHistogram(leastHoursWorked))
                .frequentBreaks(breaks.entries)
                .breaksTrend(breaks.trend)
                .build();
    }

    /** Per-employee accumulator for the range — active/late days and total worked minutes. */
    private static final class TeamStat {
        int activeDays;
        int lateDays;
        int totalWorkedMinutes;
    }

    private Map<UUID, TeamStat> groupByEmployee(List<Attendance> records) {
        Map<UUID, TeamStat> stats = new HashMap<>();
        for (Attendance r : records) {
            if (r.getCheckInAt() == null) {
                continue; // no punch that day — not an "active" day for averaging purposes
            }
            TeamStat stat = stats.computeIfAbsent(r.getEmployeeUserId(), k -> new TeamStat());
            stat.activeDays++;
            if (STATUS_LATE.equals(r.getStatus())) {
                stat.lateDays++;
            }
            stat.totalWorkedMinutes += r.getWorkedMinutes() != null ? r.getWorkedMinutes() : 0;
        }
        return stats;
    }

    private long countWeekdays(LocalDate from, LocalDate to) {
        return from.datesUntil(to.plusDays(1))
                .filter(d -> d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY)
                .count();
    }

    private List<TeamNegligenceResponse.HoursBucket> buildHoursHistogram(
            List<TeamNegligenceResponse.LeastHoursEntry> entries) {
        String[] labels = {"< 4 hours", "4 - 5 hours", "5 - 6 hours", "6 - 7 hours", "7 - 8 hours", ">= 8 hours"};
        int[] counts = new int[labels.length];
        for (TeamNegligenceResponse.LeastHoursEntry e : entries) {
            double h = e.getAvgHoursPerDay();
            int idx = h < 4 ? 0 : h < 5 ? 1 : h < 6 ? 2 : h < 7 ? 3 : h < 8 ? 4 : 5;
            counts[idx]++;
        }
        int total = entries.size();
        List<TeamNegligenceResponse.HoursBucket> buckets = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            double pct = total == 0 ? 0 : counts[i] * 100.0 / total;
            buckets.add(TeamNegligenceResponse.HoursBucket.builder()
                    .label(labels[i]).count(counts[i]).pct(round1(pct)).build());
        }
        return buckets;
    }

    /** Per-employee accumulator for break count/minutes while walking punch sessions. */
    private static final class BreakStat {
        int count;
        long minutes;
    }

    /** Per-day accumulator for the trend line: total breaks and how many employees punched that day. */
    private static final class DayBreakTotals {
        int totalBreaks;
        int employeesWithSessions;
    }

    private static final class FrequentBreaksResult {
        final List<TeamNegligenceResponse.FrequentBreaksEntry> entries;
        final List<TeamNegligenceResponse.DailyAverage> trend;

        FrequentBreaksResult(List<TeamNegligenceResponse.FrequentBreaksEntry> entries,
                              List<TeamNegligenceResponse.DailyAverage> trend) {
            this.entries = entries;
            this.trend = trend;
        }
    }

    private FrequentBreaksResult computeFrequentBreaks(
            List<Attendance> records, Map<UUID, Employee> byId, LocalDate from, LocalDate to) {
        if (records.isEmpty()) {
            return new FrequentBreaksResult(List.of(), List.of());
        }
        Map<UUID, Attendance> recordById = records.stream()
                .collect(Collectors.toMap(Attendance::getId, Function.identity()));
        List<AttendancePunch> punches =
                attendancePunchRepository.findByAttendanceRecordIdInOrderByCheckInAtAsc(recordById.keySet());
        Map<UUID, List<AttendancePunch>> byRecord = punches.stream()
                .collect(Collectors.groupingBy(AttendancePunch::getAttendanceRecordId));

        Map<UUID, BreakStat> statByEmployee = new HashMap<>();
        Map<UUID, Integer> activeDaysWithPunches = new HashMap<>();
        Map<LocalDate, DayBreakTotals> dailyTotals = new HashMap<>();

        for (Map.Entry<UUID, List<AttendancePunch>> entry : byRecord.entrySet()) {
            List<AttendancePunch> sessions = entry.getValue(); // already ordered by checkInAt asc
            Attendance record = recordById.get(entry.getKey());
            if (sessions.isEmpty() || record == null) {
                continue;
            }
            UUID employeeUserId = record.getEmployeeUserId();
            activeDaysWithPunches.merge(employeeUserId, 1, Integer::sum);
            BreakStat stat = statByEmployee.computeIfAbsent(employeeUserId, k -> new BreakStat());
            DayBreakTotals dayTotals = dailyTotals.computeIfAbsent(record.getWorkDate(), k -> new DayBreakTotals());
            dayTotals.employeesWithSessions++;

            // sessions.size() - 1 gaps = the day's breaks; the first session isn't a break.
            for (int i = 0; i < sessions.size() - 1; i++) {
                AttendancePunch current = sessions.get(i);
                AttendancePunch next = sessions.get(i + 1);
                stat.count++;
                dayTotals.totalBreaks++;
                if (current.getCheckOutAt() != null) {
                    stat.minutes += Duration.between(current.getCheckOutAt(), next.getCheckInAt()).toMinutes();
                }
            }
        }

        List<TeamNegligenceResponse.FrequentBreaksEntry> entries = new ArrayList<>();
        for (Map.Entry<UUID, BreakStat> entry : statByEmployee.entrySet()) {
            if (entry.getValue().count == 0) {
                continue; // no breaks in range — excluded entirely, not shown as 0 (AC #4)
            }
            Employee employee = byId.get(entry.getKey());
            int activeDays = activeDaysWithPunches.getOrDefault(entry.getKey(), 1);
            entries.add(TeamNegligenceResponse.FrequentBreaksEntry.builder()
                    .employeeUserId(entry.getKey())
                    .fullName(employee != null ? employee.getFullName() : null)
                    .designationName(designationOf(employee))
                    .totalBreakHours(round1(entry.getValue().minutes / 60.0))
                    .totalBreakCount(entry.getValue().count)
                    .avgBreaksPerDay(round1((double) entry.getValue().count / activeDays))
                    .build());
        }
        entries.sort(Comparator.comparingDouble(TeamNegligenceResponse.FrequentBreaksEntry::getTotalBreakHours).reversed());

        List<TeamNegligenceResponse.DailyAverage> trend = from.datesUntil(to.plusDays(1))
                .map(d -> {
                    DayBreakTotals t = dailyTotals.get(d);
                    double avg = (t == null || t.employeesWithSessions == 0)
                            ? 0 : (double) t.totalBreaks / t.employeesWithSessions;
                    return TeamNegligenceResponse.DailyAverage.builder().date(d).avgBreaks(round1(avg)).build();
                })
                .toList();

        return new FrequentBreaksResult(entries, trend);
    }

    private String designationOf(Employee employee) {
        return employee != null && employee.getDesignation() != null ? employee.getDesignation().getTitle() : null;
    }

    private double round1(double value) {
        return Math.round(value * 10) / 10.0;
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
                .workedMinutes(worked)
                .status(record.getStatus())
                .lateByMinutes(record.getLateByMinutes())
                .fullDay(worked == null ? null : worked >= props.getFullDayMinHours() * 60)
                .source(record.getSource())
                .workMode(employee.getWorkMode())
                .build();
    }
}
