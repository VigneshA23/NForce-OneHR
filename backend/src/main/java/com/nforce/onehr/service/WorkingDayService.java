package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.WorkingDaySchedule;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Holiday;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.WeeklyOffPolicy;
import com.nforce.onehr.repository.HolidayRepository;
import com.nforce.onehr.repository.LeaveRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
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
 * assigned), location holidays, and approved leave all count against the expected days.
 * Deliberately NOT a simple attendance-row count: an employee with zero punches in a range still
 * has a non-zero expected-working-days denominator whenever they had working days on file.
 *
 * <p>Approved leave — full-day or half-day — removes the whole date from the schedule. Every
 * consumer of this service (Team Effort, Team Punctuality) operates at whole-day granularity, so
 * a half-day leave is not split into a fractional working day.
 *
 * <p>Bulk callers must pass {@link Employee} rows already fetched with {@code location} and
 * {@code weeklyOffPolicy} joined (see {@code EmployeeRepository.findAllByIdWithScheduleDetails})
 * — this service issues exactly one holiday query and one leave query for the whole batch,
 * regardless of team size.
 */
@Service
@RequiredArgsConstructor
public class WorkingDayService {

    private static final String LEAVE_STATUS_APPROVED = "APPROVED";
    private static final Set<DayOfWeek> DEFAULT_WEEKLY_OFF = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    private final HolidayRepository holidayRepository;
    private final LeaveRequestRepository leaveRequestRepository;

    public WorkingDaySchedule computeExpectedWorkingDays(Employee employee, LocalDate from, LocalDate to) {
        return computeExpectedWorkingDaysBulk(List.of(employee), from, to).get(employee.getUserId());
    }

    public Map<UUID, WorkingDaySchedule> computeExpectedWorkingDaysBulk(List<Employee> employees, LocalDate from, LocalDate to) {
        if (employees.isEmpty() || from.isAfter(to)) {
            return Map.of();
        }

        Map<UUID, Set<LocalDate>> holidaysByLocation = loadHolidaysByLocation(employees);
        Map<UUID, Set<LocalDate>> leaveDatesByEmployee = loadApprovedLeaveDates(employees, from, to);

        Map<UUID, WorkingDaySchedule> result = new HashMap<>(employees.size());
        for (Employee employee : employees) {
            Set<LocalDate> holidays = employee.getLocation() != null
                    ? holidaysByLocation.getOrDefault(employee.getLocation().getId(), Set.of())
                    : Set.of();
            Set<LocalDate> leaveDates = leaveDatesByEmployee.getOrDefault(employee.getUserId(), Set.of());
            Set<DayOfWeek> offDays = weeklyOffDaysOf(employee);

            LocalDate rangeStart = clampToJoiningDate(employee, from);
            Set<LocalDate> workingDates = new TreeSet<>();
            if (!rangeStart.isAfter(to)) {
                rangeStart.datesUntil(to.plusDays(1)).forEach(date -> {
                    if (offDays.contains(date.getDayOfWeek())) return;
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

    /** Comma-separated DayOfWeek names on the assigned policy, else the Saturday/Sunday fallback. */
    private Set<DayOfWeek> weeklyOffDaysOf(Employee employee) {
        WeeklyOffPolicy policy = employee.getWeeklyOffPolicy();
        if (policy == null || policy.getOffDays() == null || policy.getOffDays().isBlank()) {
            return DEFAULT_WEEKLY_OFF;
        }
        return Arrays.stream(policy.getOffDays().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(DayOfWeek::valueOf)
                .collect(Collectors.toSet());
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
