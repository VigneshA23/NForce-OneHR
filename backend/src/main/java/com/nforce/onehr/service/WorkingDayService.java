package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.WorkingDaySchedule;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeWeeklyOffPolicyAssignment;
import com.nforce.onehr.entity.Holiday;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.WeeklyOffPolicyVersion;
import com.nforce.onehr.repository.HolidayRepository;
import com.nforce.onehr.repository.LeaveRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Single source of truth for "how many days was this employee expected to work" over a date
 * range — joining date, weekly-off policy (falling back to Saturday/Sunday when none is
 * effectively assigned on a given date), location holidays, and approved leave all count against
 * the expected days. Deliberately NOT a simple attendance-row count: an employee with zero
 * punches in a range still has a non-zero expected-working-days denominator whenever they had
 * working days on file.
 *
 * <p>Weekly-off resolution is effective-dated (Phase 3) — see {@link #weeklyOffDaysResolver},
 * the single reusable batch-resolution path this class and {@code ExceptionService} both use.
 * WHICH Weekly Off Policy governs an employee, and WHAT that policy defined, are each resolved
 * for the SPECIFIC date being evaluated (never once for a whole range, and never from {@code
 * Employee.weeklyOffPolicy}, a best-effort display cache) via {@link
 * EmployeeWeeklyOffPolicyAssignmentResolver}/{@link WeeklyOffPolicyVersionResolver}. No effective
 * assignment at all falls back to Saturday/Sunday — the same explicit, pre-existing behavior this
 * phase preserves rather than replaces (D1: never a silently-assigned default policy).
 *
 * <p>Approved leave — full-day or half-day — removes the whole date from the schedule. Every
 * consumer of this service (Team Effort, Team Punctuality) operates at whole-day granularity, so
 * a half-day leave is not split into a fractional working day.
 *
 * <p>Bulk callers must pass {@link Employee} rows already fetched with {@code location} joined
 * (see {@code EmployeeRepository.findAllByIdWithScheduleDetails}) — this service issues exactly
 * one holiday query, one leave query, one weekly-off-assignment query and one policy-version
 * query for the whole batch, regardless of team size or date-range length.
 */
@Service
@RequiredArgsConstructor
public class WorkingDayService {

    private static final String LEAVE_STATUS_APPROVED = "APPROVED";
    private static final Set<DayOfWeek> DEFAULT_WEEKLY_OFF = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    private final HolidayRepository holidayRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final EmployeeWeeklyOffPolicyAssignmentResolver employeeWeeklyOffPolicyAssignmentResolver;
    private final WeeklyOffPolicyVersionResolver weeklyOffPolicyVersionResolver;

    public WorkingDaySchedule computeExpectedWorkingDays(Employee employee, LocalDate from, LocalDate to) {
        return computeExpectedWorkingDaysBulk(List.of(employee), from, to).get(employee.getUserId());
    }

    /**
     * The first working day strictly after {@code from} — the earliest date afterward that is
     * neither a weekly off (per {@code employee}'s own effective-dated Policy assignment, resolved
     * fresh for each candidate date — falling back to Saturday/Sunday exactly like {@link
     * #computeExpectedWorkingDaysBulk}) nor a {@code employee}'s Location holiday. Reuses this
     * class's own weekly-off/holiday resolution ({@link #weeklyOffDaysResolver}/{@link
     * #loadHolidaysByLocation}) rather than a second, independently-duplicated implementation.
     *
     * <p><b>Not currently called by any production code.</b> This was written to compute a
     * brand-new {@code EmployeeShiftAssignment}'s {@code effectiveFrom} automatically, but the
     * shipped ONEHR-355 business rule instead takes {@code effectiveFrom} directly from the
     * admin's own explicit choice in every create path ({@code EmployeeService#createEmployee}/
     * {@code UserManagementService#createUser}/{@code EmployeeAssignmentService#bulkUpdateShift}/
     * CSV import) — see each of their own Javadoc ("Deliberately NOT derived from... 'next
     * working day'"). Kept (with its own test coverage) as a small, independently correct,
     * reusable utility rather than removed, in case a future flow legitimately needs "the next
     * working day after X" — but it is not part of any currently-shipped effective-from
     * derivation. Approved leave is deliberately NOT consulted here (unlike
     * {@code computeExpectedWorkingDaysBulk}) — a future leave request has no bearing on which
     * day is a working day for this kind of lookup.
     */
    // A generous ceiling nothing legitimate ever approaches (holiday runs are days, weekly-off
    // patterns repeat within a week) — exists purely to fail loudly rather than hang the
    // employee-creation request forever if a WeeklyOffPolicy with all 7 days off (which
    // OrgService's own create/update validation rejects — see its own comment) ever reaches this
    // method anyway, e.g. through data that predates that validation or was written directly.
    private static final int NEXT_WORKING_DAY_SEARCH_LIMIT_DAYS = 366;

    public LocalDate nextWorkingDay(Employee employee, LocalDate from) {
        WeeklyOffBatchResolver offDaysResolver = weeklyOffDaysResolver(List.of(employee.getUserId()));
        Set<LocalDate> holidays = employee.getLocation() != null
                ? loadHolidaysByLocation(List.of(employee)).getOrDefault(employee.getLocation().getId(), Set.of())
                : Set.of();
        LocalDate candidate = from.plusDays(1);
        int daysChecked = 0;
        while (offDaysResolver.offDaysOn(employee.getUserId(), candidate).contains(candidate.getDayOfWeek())
                || holidays.contains(candidate)) {
            if (++daysChecked > NEXT_WORKING_DAY_SEARCH_LIMIT_DAYS) {
                throw new IllegalStateException(
                        "No working day found within " + NEXT_WORKING_DAY_SEARCH_LIMIT_DAYS + " days of " + from
                                + " for employee " + employee.getUserId() + " — this almost certainly means their "
                                + "Weekly Off Policy has every day of the week marked off, which should never have "
                                + "been possible to save. Contact an administrator.");
            }
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    public Map<UUID, WorkingDaySchedule> computeExpectedWorkingDaysBulk(List<Employee> employees, LocalDate from, LocalDate to) {
        if (employees.isEmpty() || from.isAfter(to)) {
            return Map.of();
        }

        Map<UUID, Set<LocalDate>> holidaysByLocation = loadHolidaysByLocation(employees);
        Map<UUID, Set<LocalDate>> leaveDatesByEmployee = loadApprovedLeaveDates(employees, from, to);
        WeeklyOffBatchResolver offDaysResolver = weeklyOffDaysResolver(
                employees.stream().map(Employee::getUserId).collect(Collectors.toSet()));

        Map<UUID, WorkingDaySchedule> result = new HashMap<>(employees.size());
        for (Employee employee : employees) {
            Set<LocalDate> holidays = employee.getLocation() != null
                    ? holidaysByLocation.getOrDefault(employee.getLocation().getId(), Set.of())
                    : Set.of();
            Set<LocalDate> leaveDates = leaveDatesByEmployee.getOrDefault(employee.getUserId(), Set.of());

            LocalDate rangeStart = clampToJoiningDate(employee, from);
            Set<LocalDate> workingDates = new TreeSet<>();
            if (!rangeStart.isAfter(to)) {
                rangeStart.datesUntil(to.plusDays(1)).forEach(date -> {
                    // Resolved PER DATE, not once for the whole range — a policy reassignment or a
                    // policy-definition edit partway through the range must only affect the dates
                    // from its own effective date onward (see WeeklyOffBatchResolver's own Javadoc).
                    if (offDaysResolver.offDaysOn(employee.getUserId(), date).contains(date.getDayOfWeek())) return;
                    if (holidays.contains(date)) return;
                    if (leaveDates.contains(date)) return;
                    workingDates.add(date);
                });
            }
            result.put(employee.getUserId(), WorkingDaySchedule.builder()
                    .employeeUserId(employee.getUserId())
                    .workingDates(workingDates)
                    .build());
        }
        return result;
    }

    /** Joining date clamps the range's start — a day before the employee joined was never expected. */
    private LocalDate clampToJoiningDate(Employee employee, LocalDate from) {
        LocalDate joiningDate = employee.getJoiningDate();
        return joiningDate != null && joiningDate.isAfter(from) ? joiningDate : from;
    }

    /**
     * The single reusable, batch-resolved weekly-off resolution path every multi-date/multi-
     * employee consumer (this class, {@code ExceptionService}) uses — one assignment-history query
     * and one policy-version-history query for the WHOLE batch, regardless of how many employees
     * or dates are ultimately resolved against it, instead of a query per employee per date.
     * {@link WeeklyOffBatchResolver#offDaysOn} then resolves purely in-memory.
     */
    public WeeklyOffBatchResolver weeklyOffDaysResolver(Collection<UUID> employeeUserIds) {
        Map<UUID, List<EmployeeWeeklyOffPolicyAssignment>> assignmentsByEmployee =
                employeeWeeklyOffPolicyAssignmentResolver.resolveHistoryBatch(employeeUserIds);
        Set<UUID> policyIds = assignmentsByEmployee.values().stream()
                .flatMap(List::stream)
                .map(a -> a.getWeeklyOffPolicy().getId())
                .collect(Collectors.toSet());
        Map<UUID, List<WeeklyOffPolicyVersion>> versionsByPolicy = weeklyOffPolicyVersionResolver.resolveHistoryBatch(policyIds);
        return new WeeklyOffBatchResolver(assignmentsByEmployee, versionsByPolicy);
    }

    /**
     * An in-memory, per-(employee, date) weekly-off resolution closure over a batch already
     * fetched by {@link #weeklyOffDaysResolver} — no further database access. Resolves the
     * Policy assignment governing {@code date} for {@code employeeUserId}, then that Policy's own
     * version governing {@code date}; no effective assignment (or, as an invariant-violation
     * fallback, no effective version on an assignment that does exist) falls back to Saturday/
     * Sunday — the same explicit, pre-existing no-assignment behavior this phase preserves.
     */
    public static final class WeeklyOffBatchResolver {
        private final Map<UUID, List<EmployeeWeeklyOffPolicyAssignment>> assignmentsByEmployee;
        private final Map<UUID, List<WeeklyOffPolicyVersion>> versionsByPolicy;

        private WeeklyOffBatchResolver(Map<UUID, List<EmployeeWeeklyOffPolicyAssignment>> assignmentsByEmployee,
                                        Map<UUID, List<WeeklyOffPolicyVersion>> versionsByPolicy) {
            this.assignmentsByEmployee = assignmentsByEmployee;
            this.versionsByPolicy = versionsByPolicy;
        }

        public Set<DayOfWeek> offDaysOn(UUID employeeUserId, LocalDate date) {
            List<EmployeeWeeklyOffPolicyAssignment> history = assignmentsByEmployee.getOrDefault(employeeUserId, List.of());
            return EmployeeWeeklyOffPolicyAssignmentResolver.governingAssignment(history, date)
                    .flatMap(assignment -> {
                        List<WeeklyOffPolicyVersion> versions = versionsByPolicy.getOrDefault(assignment.getWeeklyOffPolicy().getId(), List.of());
                        return WeeklyOffPolicyVersionResolver.governingVersion(versions, date);
                    })
                    .map(WeeklyOffPolicyVersion::offDaysSet)
                    .filter(offDays -> !offDays.isEmpty())
                    .orElse(DEFAULT_WEEKLY_OFF);
        }
    }

    private Map<UUID, Set<LocalDate>> loadHolidaysByLocation(List<Employee> employees) {
        Set<UUID> locationIds = employees.stream()
                .map(e -> e.getLocation() != null ? e.getLocation().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (locationIds.isEmpty()) {
            return Map.of();
        }
        List<Holiday> holidays = holidayRepository.findByLocation_IdInAndActiveTrue(locationIds);
        return holidays.stream()
                .collect(Collectors.groupingBy(h -> h.getLocation().getId(),
                        Collectors.mapping(Holiday::getHolidayDate, Collectors.toSet())));
    }

    private Map<UUID, Set<LocalDate>> loadApprovedLeaveDates(List<Employee> employees, LocalDate from, LocalDate to) {
        Collection<UUID> employeeIds = employees.stream().map(Employee::getUserId).collect(Collectors.toSet());
        List<LeaveRequest> approvedLeave = leaveRequestRepository
                .findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        employeeIds, LEAVE_STATUS_APPROVED, to, from);

        Map<UUID, Set<LocalDate>> leaveDatesByEmployee = new HashMap<>();
        for (LeaveRequest leave : approvedLeave) {
            LocalDate start = leave.getStartDate().isBefore(from) ? from : leave.getStartDate();
            LocalDate end = leave.getEndDate().isAfter(to) ? to : leave.getEndDate();
            if (start.isAfter(end)) continue;
            leaveDatesByEmployee
                    .computeIfAbsent(leave.getEmployeeUserId(), k -> new HashSet<>())
                    .addAll(start.datesUntil(end.plusDays(1)).collect(Collectors.toSet()));
        }
        return leaveDatesByEmployee;
    }
}
