package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.attendance.PolicyEvaluationContext;
import com.nforce.onehr.dto.attendance.WorkingDaySchedule;
import com.nforce.onehr.dto.exceptions.ExceptionResponse;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExceptionService {

    private static final Set<String> HR_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");
    private static final Set<String> PENDING_REGULARIZATION_STATUSES = Set.of("PENDING", "PARTIALLY_APPROVED");

    // Still detected and evaluated against the Penalization Policy exactly as before (see
    // detectExceptions/runScheduledPenaltyEvaluation) — these three just no longer surface as
    // rows on the Exception Dashboard itself, per explicit request. Removing an entry here only
    // changes what getExceptionsForCaller returns, never what gets detected or penalized.
    private static final Set<String> HIDDEN_FROM_EXCEPTION_DASHBOARD = Set.of(
            ExceptionType.NO_ATTENDANCE, ExceptionType.WORK_HOURS_SHORTAGE, ExceptionType.LEAVE_ATTENDANCE_CONFLICT);

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final AttendanceExceptionRepository attendanceExceptionRepository;
    private final AttendanceRepository attendanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final RegularizationRequestRepository regularizationRequestRepository;
    private final AttendanceProperties attendanceProperties;
    private final EmailService emailService;
    private final AttendancePenaltyEvaluationService attendancePenaltyEvaluationService;
    private final WorkingDayService workingDayService;
    private final PenalizationPolicyVersionRepository penalizationPolicyVersionRepository;
    private final HolidayRepository holidayRepository;
    private final PenalizationPolicyService penalizationPolicyService;

    /**
     * HR Admin + Super Admin see company-wide exceptions; Manager sees only current
     * direct reports (via EmployeeManagerHistory). Scope is resolved from the caller's
     * roles only — never client-supplied. HR/Super Admin takes precedence over Manager
     * for any user holding both roles.
     *
     * This dashboard is an individual-contributor view only: an exception subject must
     * hold the EMPLOYEE role and none of MANAGER/HR_ADMIN/SUPER_ADMIN (see
     * UserRepository.findEmployeeRoleUserIds()). Admin/HR/Manager accounts never appear
     * as exception subjects, company-wide or as a direct report, even if they were also
     * granted EMPLOYEE (e.g. to punch in/out themselves) or their own attendance would
     * otherwise qualify.
     */
    @Transactional
    public List<ExceptionResponse> getExceptionsForCaller(String actorEmail, LocalDate from, LocalDate to) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
        Set<String> roleCodes = actor.getRoles().stream().map(Role::getCode).collect(Collectors.toSet());
        Set<UUID> employeeRoleIds = userRepository.findEmployeeRoleUserIds();

        List<UUID> scopeIds;
        if (roleCodes.stream().anyMatch(HR_ROLES::contains)) {
            scopeIds = new java.util.ArrayList<>(employeeRoleIds);
        } else if (roleCodes.contains("MANAGER")) {
            scopeIds = historyRepository.findByManagerUserIdAndEffectiveToIsNull(actor.getId()).stream()
                    .map(EmployeeManagerHistory::getEmployeeUserId)
                    .filter(employeeRoleIds::contains)
                    .collect(Collectors.toList());
        } else {
            throw new AccessDeniedException("Not authorized to view exceptions");
        }

        detectExceptions(scopeIds, from, to);

        List<AttendanceException> exceptions = attendanceExceptionRepository
                .findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(scopeIds, from, to);

        List<ExceptionResponse> responses = exceptions.stream()
                .filter(e -> !HIDDEN_FROM_EXCEPTION_DASHBOARD.contains(e.getExceptionType()))
                .map(this::toResponse)
                .collect(Collectors.toCollection(java.util.ArrayList::new));
        responses.addAll(pendingLeaveApprovals(scopeIds, from, to));
        responses.sort(Comparator.comparing(ExceptionResponse::getExceptionDate)
                .thenComparing(ExceptionResponse::getDetectedAt)
                .reversed());
        return responses;
    }

    /**
     * A leave request still awaiting approval, surfaced as a dashboard row alongside real
     * attendance exceptions — see ExceptionType.PENDING_LEAVE_APPROVAL's javadoc. Dated by when
     * it was requested (createdAt), not its leave start date, so the dashboard's From/To filter
     * means the same thing here as it does for every other row: "when did this need attention."
     */
    private List<ExceptionResponse> pendingLeaveApprovals(Collection<UUID> scopeIds, LocalDate from, LocalDate to) {
        return leaveRequestRepository.findByEmployeeUserIdInAndStatusOrderByCreatedAtAsc(scopeIds, "PENDING").stream()
                .filter(r -> {
                    LocalDate requestedOn = r.getCreatedAt().toLocalDate();
                    return !requestedOn.isBefore(from) && !requestedOn.isAfter(to);
                })
                .map(r -> {
                    Optional<Employee> employee = employeeRepository.findById(r.getEmployeeUserId());
                    return ExceptionResponse.builder()
                            .id(r.getId())
                            .employeeUserId(r.getEmployeeUserId())
                            .employeeCode(employee.map(Employee::getEmployeeCode).orElse(null))
                            .employeeFullName(employee.map(Employee::getFullName).orElse(null))
                            .exceptionDate(r.getCreatedAt().toLocalDate())
                            .exceptionType(ExceptionType.PENDING_LEAVE_APPROVAL)
                            .status("OPEN")
                            .detectedAt(r.getCreatedAt())
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * Section 45's background-processing entry point: the same detection/evaluation pipeline
     * {@link #getExceptionsForCaller} already runs on every dashboard load, but company-wide and
     * on a schedule (see {@code PenaltyEvaluationScheduler}) — so a Penalization Policy match is
     * never missed just because nobody opened the Exceptions/Penalties dashboard that day. Looks
     * back {@code lookbackDays} calendar days from yesterday (never "today", an in-progress day),
     * which must comfortably cover the longest configured buffer period + exemption cycle for any
     * policy still evaluating pending occurrences.
     */
    @Transactional
    public void runScheduledPenaltyEvaluation(int lookbackDays) {
        LocalDate today = LocalDateTime.now(ZoneId.of(attendanceProperties.getZone())).toLocalDate();
        LocalDate to = today.minusDays(1);
        LocalDate from = to.minusDays(Math.max(lookbackDays, 1) - 1L);
        if (to.isBefore(from)) {
            return;
        }
        Set<UUID> scopeIds = userRepository.findEmployeeRoleUserIds();
        if (scopeIds.isEmpty()) {
            return;
        }
        detectExceptions(new java.util.ArrayList<>(scopeIds), from, to);
    }

    /**
     * Reads real attendance_records for the scope/date range and upserts all exception
     * types into attendance_exceptions:
     *  - LATE_ARRIVAL: the check-in was already flagged late by AttendanceService against
     *    the same shift-start/grace configuration — reused here rather than re-derived.
     *  - MISSING_PUNCH: a past day (never today, which may still legitimately be open) has
     *    a check-in but no check-out.
     *  - LEAVE_ATTENDANCE_CONFLICT: an approved leave request covers the same day a
     *    check-in was also recorded.
     *
     * <p>The moment a discrepancy is first detected, {@code upsertException} also evaluates it
     * against the configured Penalization Policy (see {@link #evaluatePolicy}) — this dashboard
     * load (HR/Super Admin viewing company-wide exceptions, or a Manager viewing their team's) is
     * the existing, already-invoked production trigger; no scheduler is introduced.
     */
    private void detectExceptions(Collection<UUID> scopeIds, LocalDate from, LocalDate to) {
        List<UUID> scopeIdList = new java.util.ArrayList<>(scopeIds);
        List<Attendance> records = attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(scopeIdList, from, to);

        LocalDate today = LocalDateTime.now(ZoneId.of(attendanceProperties.getZone())).toLocalDate();

        List<LeaveRequest> approvedLeave = leaveRequestRepository
                .findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        scopeIdList, "APPROVED", to, from);

        Set<String> leaveCoveredDays = approvedLeave.stream()
                .flatMap(leave -> leave.getStartDate().datesUntil(leave.getEndDate().plusDays(1))
                        .map(date -> leave.getEmployeeUserId() + "|" + date))
                .collect(Collectors.toSet());

        // Was attendanceProperties.getShiftStart() (the org-wide fallback) for every employee
        // regardless of their own assigned Shift — the LATE_ARRIVAL decision/count themselves
        // were unaffected (reused correctly from record.getLateByMinutes() below), but the
        // "expected" time shown/emailed for this exception was wrong for anyone not on the
        // default shift. Mirrors AttendanceService.resolveShiftStart's own fallback rule.
        Map<UUID, Employee> employeesById = employeeRepository.findAllByIdWithScheduleDetails(scopeIdList).stream()
                .collect(Collectors.toMap(Employee::getUserId, e -> e));

        for (Attendance record : records) {
            if (record.getLateByMinutes() != null && record.getLateByMinutes() > 0) {
                Employee employee = employeesById.get(record.getEmployeeUserId());
                LocalTime expectedShiftStart = employee != null && employee.getShift() != null
                        ? employee.getShift().getStartTime() : attendanceProperties.getShiftStart();
                upsertException(record, ExceptionType.LATE_ARRIVAL,
                        expectedShiftStart, record.getCheckInAt().toLocalTime(),
                        record.getLateByMinutes());
            }
            if (record.getCheckInAt() != null && record.getCheckOutAt() == null && record.getWorkDate().isBefore(today)) {
                upsertException(record, ExceptionType.MISSING_PUNCH,
                        null, record.getCheckInAt().toLocalTime(), null);
            }
            if (record.getCheckInAt() != null && leaveCoveredDays.contains(record.getEmployeeUserId() + "|" + record.getWorkDate())) {
                upsertException(record, ExceptionType.LEAVE_ATTENDANCE_CONFLICT,
                        null, record.getCheckInAt().toLocalTime(), null);
            }
        }

        detectNoAttendanceAndShortage(scopeIdList, records, from, to, today);
    }

    /**
     * NO_ATTENDANCE (Section 10) and WORK_HOURS_SHORTAGE (Section 18) both require comparing an
     * expected working day against what actually happened — the loop above can never produce
     * either one since it only ever iterates {@code Attendance} rows that already exist (see
     * {@code ExceptionType}'s "reserved" javadoc). Reuses {@link WorkingDayService} — the existing
     * "how many days was this employee expected to work" source of truth already used by Team
     * Effort/Punctuality — instead of inventing a second expected-working-day calculation.
     *
     * <p>Never evaluated for "today" (an in-progress day isn't a discrepancy yet, matching the
     * existing MISSING_PUNCH convention).
     */
    private void detectNoAttendanceAndShortage(List<UUID> scopeIdList, List<Attendance> records,
                                                LocalDate from, LocalDate to, LocalDate today) {
        LocalDate rangeEnd = to.isBefore(today) ? to : today.minusDays(1);
        if (rangeEnd.isBefore(from)) {
            return;
        }
        List<Employee> employees = employeeRepository.findAllByIdWithScheduleDetails(scopeIdList);
        Map<UUID, WorkingDaySchedule> schedules = workingDayService.computeExpectedWorkingDaysBulk(employees, from, rangeEnd);

        Map<String, Attendance> byEmployeeDate = records.stream()
                .collect(Collectors.toMap(r -> r.getEmployeeUserId() + "|" + r.getWorkDate(), r -> r, (a, b) -> a));

        for (Employee employee : employees) {
            WorkingDaySchedule schedule = schedules.get(employee.getUserId());
            if (schedule == null) {
                continue;
            }
            for (LocalDate date : schedule.getWorkingDates()) {
                Attendance existing = byEmployeeDate.get(employee.getUserId() + "|" + date);
                if (existing == null) {
                    // No punch at all — build a transient (never persisted) Attendance carrying
                    // just enough for upsertException/evaluatePolicy to work from; workedMinutes
                    // is 0, matching "1h30m against a threshold" style facts elsewhere.
                    Attendance noAttendance = Attendance.builder()
                            .employeeUserId(employee.getUserId()).workDate(date).workedMinutes(0).build();
                    upsertException(noAttendance, ExceptionType.NO_ATTENDANCE, null, null, null);
                } else if (existing.getCheckOutAt() != null && existing.getWorkedMinutes() != null
                        && employee.getShift() != null) {
                    long shiftMinutes = Duration.between(
                            employee.getShift().getStartTime(), employee.getShift().getEndTime()).toMinutes();
                    if (shiftMinutes > 0 && existing.getWorkedMinutes() < shiftMinutes) {
                        upsertException(existing, ExceptionType.WORK_HOURS_SHORTAGE,
                                employee.getShift().getEndTime(), existing.getCheckOutAt().toLocalTime(), null);
                    }
                }
            }
        }

        detectAdjoiningPenalties(employees, schedules, byEmployeeDate, from, rangeEnd);
    }

    /**
     * Section 12/13: No Attendance's adjoining-holiday/adjoining-week-off "sandwich" rules —
     * explicitly called out as NOT implemented in Phase 1 (no multi-day look-around detection
     * existed anywhere in this codebase). When enabled and the configured condition is met, the
     * holiday/week-off date itself becomes an ADDITIONAL {@code NO_ATTENDANCE} occurrence — the
     * same evaluation path {@link #detectNoAttendanceAndShortage} already uses, not a second one.
     *
     * <p>Scoped to the requested {@code [from, to]} window only — a holiday/week-off whose
     * adjoining no-attendance day(s) fall outside that window is not evaluated this call (the
     * scheduled background job's wide rolling lookback window means this is not a practical gap
     * for anything but a boundary date).
     *
     * <p>Simplification, disclosed: a multi-day holiday block (consecutive {@code Holiday} rows)
     * is evaluated per individual date, not as one merged block.
     */
    private void detectAdjoiningPenalties(List<Employee> employees, Map<UUID, WorkingDaySchedule> schedules,
                                           Map<String, Attendance> byEmployeeDate, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return;
        }
        Map<UUID, Set<LocalDate>> holidaysByLocation = loadHolidayDatesByLocation(employees, from, to);
        Map<String, Boolean> halfDayByEmployeeDate = loadHalfDayLeaveByEmployeeDate(employees, from, to);

        for (Employee employee : employees) {
            WorkingDaySchedule schedule = schedules.get(employee.getUserId());
            if (schedule == null) {
                continue;
            }
            PenalizationPolicyVersion version = resolveEffectiveVersion(resolveAssignedOrDefaultPolicyId(employee), to);
            if (version == null || (!version.isNaAdjoiningHolidayEnabled() && !version.isNaAdjoiningWeekoffEnabled())) {
                continue;
            }
            Set<LocalDate> holidayDates = employee.getLocation() != null
                    ? holidaysByLocation.getOrDefault(employee.getLocation().getId(), Set.of()) : Set.of();
            Set<DayOfWeek> offDays = weeklyOffDaysOf(employee);

            for (LocalDate date : from.datesUntil(to.plusDays(1)).toList()) {
                if (version.isNaAdjoiningHolidayEnabled() && holidayDates.contains(date)) {
                    evaluateAdjoiningDay(employee, version, date, version.getNaAdjoiningHolidayCondition(),
                            version.getNaAdjoiningHolidayCalendarDayThreshold(), version.isNaAdjoiningHolidayIgnoreHalfDayLeave(),
                            byEmployeeDate, holidayDates, offDays, halfDayByEmployeeDate);
                }
                if (version.isNaAdjoiningWeekoffEnabled() && offDays.contains(date.getDayOfWeek()) && !holidayDates.contains(date)) {
                    evaluateAdjoiningDay(employee, version, date, version.getNaAdjoiningWeekoffCondition(),
                            version.getNaAdjoiningWeekoffCalendarDayThreshold(), version.isNaAdjoiningWeekoffIgnoreHalfDayLeave(),
                            byEmployeeDate, holidayDates, offDays, halfDayByEmployeeDate);
                }
            }
        }
    }

    private void evaluateAdjoiningDay(Employee employee, PenalizationPolicyVersion version, LocalDate specialDate,
                                       String condition, Integer thresholdConfig, boolean ignoreHalfDayLeave,
                                       Map<String, Attendance> byEmployeeDate, Set<LocalDate> holidayDates,
                                       Set<DayOfWeek> offDays, Map<String, Boolean> halfDayByEmployeeDate) {
        int threshold = thresholdConfig != null && thresholdConfig > 0 ? thresholdConfig : 1;
        boolean beforeSatisfied = isConsecutiveNoAttendanceRun(employee, specialDate.minusDays(1), -1, threshold,
                byEmployeeDate, holidayDates, offDays, halfDayByEmployeeDate, ignoreHalfDayLeave);
        boolean afterSatisfied = isConsecutiveNoAttendanceRun(employee, specialDate.plusDays(1), 1, threshold,
                byEmployeeDate, holidayDates, offDays, halfDayByEmployeeDate, ignoreHalfDayLeave);

        boolean triggered = switch (condition == null ? "ANY" : condition) {
            case "SANDWICHED" -> beforeSatisfied && afterSatisfied;
            case "BEFORE" -> beforeSatisfied;
            case "AFTER" -> afterSatisfied;
            default -> beforeSatisfied || afterSatisfied;
        };
        if (!triggered) {
            return;
        }
        // The holiday/week-off date itself becomes an additional no-attendance occurrence —
        // "sandwiching" a holiday/week-off between absences now also costs the holiday/week-off.
        Attendance synthetic = Attendance.builder()
                .employeeUserId(employee.getUserId()).workDate(specialDate).workedMinutes(0).build();
        upsertException(synthetic, ExceptionType.NO_ATTENDANCE, null, null, null);
    }

    /** {@code count} consecutive calendar days starting at {@code start}, stepping by {@code direction}, that all qualify as "no attendance" for adjoining-rule purposes. */
    private boolean isConsecutiveNoAttendanceRun(Employee employee, LocalDate start, int direction, int count,
                                                  Map<String, Attendance> byEmployeeDate, Set<LocalDate> holidayDates,
                                                  Set<DayOfWeek> offDays, Map<String, Boolean> halfDayByEmployeeDate,
                                                  boolean ignoreHalfDayLeave) {
        LocalDate date = start;
        for (int i = 0; i < count; i++) {
            if (employee.getJoiningDate() != null && date.isBefore(employee.getJoiningDate())) {
                return false;
            }
            if (holidayDates.contains(date) || offDays.contains(date.getDayOfWeek())) {
                return false; // another holiday/week-off breaks the run, not a no-attendance day itself
            }
            String key = employee.getUserId() + "|" + date;
            Boolean halfDay = halfDayByEmployeeDate.get(key);
            if (halfDay != null) {
                if (Boolean.TRUE.equals(halfDay)) {
                    if (ignoreHalfDayLeave) {
                        return false; // a half-day leave breaks the run when configured to be ignored
                    }
                    // else: treated as a no-attendance day for this purpose — continue the run.
                } else {
                    return false; // a full-day leave is a legitimate absence, not a no-attendance day
                }
            } else if (byEmployeeDate.containsKey(key)) {
                return false; // attendance was recorded — not a no-attendance day
            }
            date = date.plusDays(direction);
        }
        return true;
    }

    private Map<UUID, Set<LocalDate>> loadHolidayDatesByLocation(List<Employee> employees, LocalDate from, LocalDate to) {
        Set<UUID> locationIds = employees.stream()
                .map(e -> e.getLocation() != null ? e.getLocation().getId() : null)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (locationIds.isEmpty()) {
            return Map.of();
        }
        return holidayRepository.findByLocation_IdInAndActiveTrue(locationIds).stream()
                .filter(h -> !h.getHolidayDate().isBefore(from.minusDays(7)) && !h.getHolidayDate().isAfter(to.plusDays(7)))
                .collect(Collectors.groupingBy(h -> h.getLocation().getId(),
                        Collectors.mapping(com.nforce.onehr.entity.Holiday::getHolidayDate, Collectors.toSet())));
    }

    /** {@code true} = half-day leave, {@code false} = full-day leave, absent = no leave that date. */
    private Map<String, Boolean> loadHalfDayLeaveByEmployeeDate(List<Employee> employees, LocalDate from, LocalDate to) {
        List<UUID> employeeIds = employees.stream().map(Employee::getUserId).toList();
        if (employeeIds.isEmpty()) {
            return Map.of();
        }
        List<LeaveRequest> approvedLeave = leaveRequestRepository
                .findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        employeeIds, "APPROVED", to.plusDays(7), from.minusDays(7));
        Map<String, Boolean> result = new java.util.HashMap<>();
        for (LeaveRequest leave : approvedLeave) {
            LocalDate start = leave.getStartDate().isBefore(from.minusDays(7)) ? from.minusDays(7) : leave.getStartDate();
            LocalDate end = leave.getEndDate().isAfter(to.plusDays(7)) ? to.plusDays(7) : leave.getEndDate();
            if (start.isAfter(end)) {
                continue;
            }
            start.datesUntil(end.plusDays(1)).forEach(date ->
                    result.put(leave.getEmployeeUserId() + "|" + date, leave.isHalfDay()));
        }
        return result;
    }

    /** Comma-separated DayOfWeek names on the assigned policy, else the Saturday/Sunday fallback — same convention as WorkingDayService. */
    private Set<DayOfWeek> weeklyOffDaysOf(Employee employee) {
        com.nforce.onehr.entity.WeeklyOffPolicy policy = employee.getWeeklyOffPolicy();
        if (policy == null || policy.getOffDays() == null || policy.getOffDays().isBlank()) {
            return Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        }
        return java.util.Arrays.stream(policy.getOffDays().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(DayOfWeek::valueOf)
                .collect(Collectors.toSet());
    }

    private void upsertException(Attendance record, String exceptionType,
                                  LocalTime expectedTime, LocalTime actualTime, Integer minutesLate) {
        UUID employeeUserId = record.getEmployeeUserId();
        LocalDate exceptionDate = record.getWorkDate();
        Optional<AttendanceException> existing = attendanceExceptionRepository
                .findByEmployeeUserIdAndExceptionDateAndExceptionType(employeeUserId, exceptionDate, exceptionType);
        boolean isNew = existing.isEmpty();

        AttendanceException exception = existing.orElseGet(() -> AttendanceException.builder()
                .employeeUserId(employeeUserId)
                .exceptionDate(exceptionDate)
                .exceptionType(exceptionType)
                .build());
        exception.setExpectedTime(expectedTime);
        exception.setActualTime(actualTime);
        exception.setMinutesLate(minutesLate);
        attendanceExceptionRepository.save(exception);

        // Email once, and evaluate the configured Penalization Policy once, the moment an
        // exception is first detected — never on later re-detection of the same row (every
        // dashboard load re-runs detectExceptions). AttendancePenaltyEvaluationService has its
        // own defensive duplicate guard regardless (see its class javadoc).
        if (isNew) {
            evaluatePolicy(record, exceptionType);
            notifyEmployee(employeeUserId, exceptionType, exceptionDate, expectedTime, actualTime, minutesLate);
        }
    }

    private void notifyEmployee(UUID employeeUserId, String exceptionType, LocalDate exceptionDate,
                                 LocalTime expectedTime, LocalTime actualTime, Integer minutesLate) {
        employeeRepository.findById(employeeUserId).ifPresent(employee -> {
            String email = employee.getUser().getEmail();
            String name = employee.getFullName();
            String managerEmail = currentManagerEmail(employeeUserId);
            if (ExceptionType.LATE_ARRIVAL.equals(exceptionType)) {
                emailService.sendLateArrivalEmail(email, managerEmail, name, exceptionDate, expectedTime, actualTime, minutesLate);
            } else if (ExceptionType.MISSING_PUNCH.equals(exceptionType)) {
                emailService.sendMissingPunchEmail(email, managerEmail, name, exceptionDate, actualTime);
            } else if (ExceptionType.LEAVE_ATTENDANCE_CONFLICT.equals(exceptionType)) {
                emailService.sendLeaveAttendanceConflictEmail(email, managerEmail, name, exceptionDate, actualTime);
            }
        });
    }

    /**
     * The one production entry point into the Penalization Policy seam: builds a
     * {@link PolicyEvaluationContext} entirely from facts {@code AttendanceService} already
     * computed (never re-derived here) and hands it to
     * {@link AttendancePenaltyEvaluationService#evaluate}, which calls the configured
     * {@link AttendancePolicyEngine} and persists an {@link AttendancePenalty} only on
     * {@code APPLY_PENALTY}. Every discrepancy type reaches here uniformly — the engine itself,
     * not this method, decides which (if any) configured section applies.
     */
    private void evaluatePolicy(Attendance record, String exceptionType) {
        UUID employeeUserId = record.getEmployeeUserId();
        LocalDate exceptionDate = record.getWorkDate();
        LocalDate today = LocalDateTime.now(ZoneId.of(attendanceProperties.getZone())).toLocalDate();

        Employee employee = employeeRepository.findById(employeeUserId).orElse(null);
        UUID assignedPolicyId = resolveAssignedOrDefaultPolicyId(employee);
        PenalizationPolicyVersion version = resolveEffectiveVersion(assignedPolicyId, exceptionDate);

        List<RegularizationRequest> regularizations = regularizationRequestRepository
                .findByEmployeeUserIdInAndAttendanceDateBetween(List.of(employeeUserId), exceptionDate, exceptionDate);
        boolean hasPending = regularizations.stream().anyMatch(r -> PENDING_REGULARIZATION_STATUSES.contains(r.getStatus()));
        boolean hasApproved = regularizations.stream().anyMatch(r -> "APPROVED".equals(r.getStatus()));

        // Section 27/34: exempt-count windows follow whichever cycle (WEEK/MONTH) the effective
        // policy configured for that section — never mixed, and never retroactively reinterpreted
        // for a cycle change (the window is derived from exceptionDate itself, not "now").
        LocalDate[] laPeriod = cyclePeriod(exceptionDate, version != null ? version.getLaExemptPeriod() : null);
        LocalDate[] mlPeriod = cyclePeriod(exceptionDate, version != null ? version.getMlExemptPeriod() : null);
        int lateArrivalCount = (int) attendanceExceptionRepository.countByEmployeeUserIdAndExceptionTypeAndExceptionDateBetween(
                employeeUserId, ExceptionType.LATE_ARRIVAL, laPeriod[0], laPeriod[1]);
        int missingLogCount = (int) attendanceExceptionRepository.countByEmployeeUserIdAndExceptionTypeAndExceptionDateBetween(
                employeeUserId, ExceptionType.MISSING_PUNCH, mlPeriod[0], mlPeriod[1]);

        boolean lateArrivalSameDay = !ExceptionType.LATE_ARRIVAL.equals(exceptionType)
                && attendanceExceptionRepository.existsByEmployeeUserIdAndExceptionDateAndExceptionType(
                        employeeUserId, exceptionDate, ExceptionType.LATE_ARRIVAL);
        boolean workHoursShortageSameDay = !ExceptionType.WORK_HOURS_SHORTAGE.equals(exceptionType)
                && attendanceExceptionRepository.existsByEmployeeUserIdAndExceptionDateAndExceptionType(
                        employeeUserId, exceptionDate, ExceptionType.WORK_HOURS_SHORTAGE);

        // Section 33: a late arrival that coincides with an unresolved missing log on the
        // immediately preceding working day is treated as "caused by" it — the cheapest fact
        // derivable from Attendance without inventing a richer causal signal that doesn't exist.
        boolean lateArrivalCausedByMissingLog = attendanceExceptionRepository
                .existsByEmployeeUserIdAndExceptionDateAndExceptionType(
                        employeeUserId, exceptionDate.minusDays(1), ExceptionType.MISSING_PUNCH);

        Integer lateMinutesTotalInPeriod = null;
        if (version != null && "TOTAL_HOURS".equals(version.getLaBasis())) {
            int grace = version.getLaGracePeriodMinutes() != null ? version.getLaGracePeriodMinutes() : 0;
            lateMinutesTotalInPeriod = attendanceExceptionRepository
                    .findByEmployeeUserIdAndExceptionTypeAndExceptionDateBetween(
                            employeeUserId, ExceptionType.LATE_ARRIVAL, laPeriod[0], laPeriod[1])
                    .stream()
                    .mapToInt(e -> Math.max(0, (e.getMinutesLate() != null ? e.getMinutesLate() : 0) - grace))
                    .sum();
        }

        PolicyEvaluationContext context = PolicyEvaluationContext.builder()
                .employeeUserId(employeeUserId)
                .attendanceDate(exceptionDate)
                .discrepancyType(exceptionType)
                .assignedPolicyId(assignedPolicyId)
                .evaluationDate(today)
                .underNoticePeriod(isUnderNoticePeriod(employee, exceptionDate))
                .hasPendingRegularization(hasPending)
                .hasApprovedRegularization(hasApproved)
                .lateMinutes(record.getLateByMinutes())
                .workedMinutes(record.getWorkedMinutes())
                .effectiveHoursPercent(computeEffectiveHoursPercent(record))
                .lateArrivalCountInPeriod(lateArrivalCount)
                .missingLogCountInPeriod(missingLogCount)
                .lateArrivalAlsoOccurredSameDay(lateArrivalSameDay)
                .workHoursShortageAlsoOccurredSameDay(workHoursShortageSameDay)
                .lateArrivalCausedByMissingLog(lateArrivalCausedByMissingLog)
                .lateMinutesTotalInPeriod(lateMinutesTotalInPeriod)
                .build();

        attendancePenaltyEvaluationService.evaluate(context);
    }

    /**
     * The employee's own assigned policy, or — if unset (e.g. a newly-created employee nobody has
     * assigned one to yet via Employee Assignments) — the org's original default policy, resolved
     * the exact same way {@link PenalizationPolicyService#resolveDefaultPolicyId()} does. Without
     * this fallback, an unassigned employee would fall through to
     * {@link PenalizationPolicyVersionRepository#findVersionsEffectiveAt} — an *unscoped* query
     * across every policy's version chain — which stopped being a safe "the one policy" lookup
     * the moment Policy List (Section 5) made multiple named policies possible: "ORDER BY version
     * DESC" with no policy filter can return an arbitrary policy's version, not the org's default.
     * Returns {@code null} only in the fully-degenerate case where no {@code PenalisationPolicy}
     * row exists at all (shouldn't happen given the V95 seed).
     */
    private UUID resolveAssignedOrDefaultPolicyId(Employee employee) {
        if (employee != null && employee.getPenalisationPolicy() != null) {
            return employee.getPenalisationPolicy().getId();
        }
        try {
            return penalizationPolicyService.resolveDefaultPolicyId();
        } catch (IllegalStateException e) {
            // No PenalisationPolicy row exists at all (shouldn't happen given the V95 seed) — no
            // default to fall back to; the caller's null-handling (noMatch) takes over from here.
            return null;
        }
    }

    private PenalizationPolicyVersion resolveEffectiveVersion(UUID assignedPolicyId, LocalDate date) {
        List<PenalizationPolicyVersion> candidates = assignedPolicyId != null
                ? penalizationPolicyVersionRepository.findVersionsEffectiveAtForPolicy(assignedPolicyId, date.atStartOfDay())
                : penalizationPolicyVersionRepository.findVersionsEffectiveAt(date.atStartOfDay());
        return candidates.stream().findFirst().orElse(null);
    }

    /** Monday-Sunday for {@code WEEK} (Section 34); calendar month for {@code MONTH} or unset. */
    private LocalDate[] cyclePeriod(LocalDate date, String periodUnit) {
        if ("WEEK".equals(periodUnit)) {
            LocalDate start = date.minusDays(date.getDayOfWeek().getValue() - 1L);
            return new LocalDate[]{start, start.plusDays(6)};
        }
        return new LocalDate[]{date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth())};
    }

    /** True when {@code date} falls within the employee's notice period, per Employee's fields. */
    private boolean isUnderNoticePeriod(Employee employee, LocalDate date) {
        if (employee == null || employee.getLastWorkingDay() == null || date.isAfter(employee.getLastWorkingDay())) {
            return false;
        }
        return employee.getNoticePeriodStartDate() == null || !date.isBefore(employee.getNoticePeriodStartDate());
    }

    /** {@code workedMinutes} as a percentage of the employee's assigned shift duration — null (not 0%) when either fact is unavailable, so the engine can tell "no data" apart from "worked nothing". */
    private Double computeEffectiveHoursPercent(Attendance record) {
        if (record.getWorkedMinutes() == null) {
            return null;
        }
        Employee employee = employeeRepository.findById(record.getEmployeeUserId()).orElse(null);
        if (employee == null || employee.getShift() == null) {
            return null;
        }
        long shiftMinutes = Duration.between(employee.getShift().getStartTime(), employee.getShift().getEndTime()).toMinutes();
        if (shiftMinutes <= 0) {
            return null;
        }
        return record.getWorkedMinutes() * 100.0 / shiftMinutes;
    }

    /** Null if the employee has no current manager on file — the email is simply sent without a cc. */
    private String currentManagerEmail(UUID employeeUserId) {
        return historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeUserId)
                .map(EmployeeManagerHistory::getManagerUserId)
                .flatMap(userRepository::findById)
                .map(User::getEmail)
                .orElse(null);
    }

    private ExceptionResponse toResponse(AttendanceException exception) {
        Optional<Employee> employee = employeeRepository.findById(exception.getEmployeeUserId());
        return ExceptionResponse.builder()
                .id(exception.getId())
                .employeeUserId(exception.getEmployeeUserId())
                .employeeCode(employee.map(Employee::getEmployeeCode).orElse(null))
                .employeeFullName(employee.map(Employee::getFullName).orElse(null))
                .exceptionDate(exception.getExceptionDate())
                .exceptionType(exception.getExceptionType())
                .expectedTime(exception.getExpectedTime())
                .actualTime(exception.getActualTime())
                .minutesLate(exception.getMinutesLate())
                .status(exception.getStatus())
                .detectedAt(exception.getDetectedAt())
                .build();
    }
}
