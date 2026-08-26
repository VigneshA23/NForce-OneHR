package com.nforce.onehr.service;

import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.LeaveDurationType;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.repository.LeaveRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single source of truth for "how many minutes was this employee expected to work on this date,"
 * after approved hourly/quarter-day leave (see LeaveDurationType) reduces the day's assigned-shift
 * duration. Both the Penalization Policy engine's fact-gathering (ExceptionService) and Attendance
 * Summary must call this rather than each computing their own adjusted figure — the whole point of
 * "adjusted expected hours" is that every consumer sees the same number.
 *
 * <p>Full-day and half-day leave are NOT handled here — those still remove the entire date via
 * {@link WorkingDayService} exactly as before this change; this service only ever reduces (never
 * zeroes) a day that remains a working day.
 */
@Service
@RequiredArgsConstructor
public class ExpectedWorkHoursService {

    private static final String LEAVE_STATUS_APPROVED = "APPROVED";
    private static final List<String> PARTIAL_HOUR_DURATION_TYPES =
            List.of(LeaveDurationType.HOURLY, LeaveDurationType.QUARTER_DAY);

    private final LeaveRequestRepository leaveRequestRepository;

    /** The employee's assigned shift duration in minutes, or null if no shift is assigned (or it has zero/negative duration). */
    public Long shiftMinutes(Employee employee) {
        if (employee == null || employee.getShift() == null) {
            return null;
        }
        long minutes = Duration.between(employee.getShift().getStartTime(), employee.getShift().getEndTime()).toMinutes();
        return minutes > 0 ? minutes : null;
    }

    /** Convenience single employee/date form of {@link #loadPartialHourLeaveByEmployeeDate} — issues its own single-row query, so prefer the bulk form when evaluating a range of employees/dates. */
    public Long adjustedExpectedMinutes(Employee employee, LocalDate date) {
        if (employee == null) {
            return null;
        }
        Map<String, LeaveRequest> byEmployeeDate =
                loadPartialHourLeaveByEmployeeDate(List.of(employee.getUserId()), date, date);
        return adjustedExpectedMinutes(employee, date, byEmployeeDate.get(key(employee.getUserId(), date)));
    }

    /** Same calculation as {@link #adjustedExpectedMinutes(Employee, LocalDate)}, given an already-resolved partial-hour leave (or null) — for bulk callers that pre-load the map once per batch instead of once per employee/date. */
    public Long adjustedExpectedMinutes(Employee employee, LocalDate date, LeaveRequest partialHourLeave) {
        Long shiftMinutes = shiftMinutes(employee);
        if (shiftMinutes == null) {
            return null;
        }
        if (partialHourLeave == null) {
            return shiftMinutes;
        }
        long reduction = switch (partialHourLeave.getDurationType()) {
            case LeaveDurationType.HOURLY -> partialHourLeave.getLeaveHours() != null
                    ? partialHourLeave.getLeaveHours().multiply(BigDecimal.valueOf(60)).longValue()
                    : 0L;
            case LeaveDurationType.QUARTER_DAY -> Math.round(shiftMinutes * 0.25);
            default -> 0L; // shouldn't reach here — loadPartialHourLeaveByEmployeeDate only returns HOURLY/QUARTER_DAY rows
        };
        return Math.max(0L, shiftMinutes - reduction);
    }

    /** Approved hourly/quarter-day leave overlapping [from, to] for the given employees, keyed by "employeeId|date" (both types are always single-day, so one leave request maps to exactly one key). */
    public Map<String, LeaveRequest> loadPartialHourLeaveByEmployeeDate(Collection<UUID> employeeIds, LocalDate from, LocalDate to) {
        if (employeeIds.isEmpty()) {
            return Map.of();
        }
        List<LeaveRequest> leaves = leaveRequestRepository
                .findByEmployeeUserIdInAndStatusAndDurationTypeInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        employeeIds, LEAVE_STATUS_APPROVED, PARTIAL_HOUR_DURATION_TYPES, to, from);
        Map<String, LeaveRequest> result = new HashMap<>();
        for (LeaveRequest leave : leaves) {
            result.put(key(leave.getEmployeeUserId(), leave.getStartDate()), leave);
        }
        return result;
    }

    private static String key(UUID employeeUserId, LocalDate date) {
        return employeeUserId + "|" + date;
    }
}
