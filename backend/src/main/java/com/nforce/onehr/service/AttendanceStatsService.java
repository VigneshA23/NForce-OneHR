package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.AttendanceStatBucket;
import com.nforce.onehr.dto.attendance.AttendanceStatsResponse;
import com.nforce.onehr.dto.attendance.WorkingDaySchedule;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * "Me vs My Team" attendance comparison, kept deliberately separate from AttendanceService's
 * manager-oversight methods (getDayForMyTeam/getMonthForMyTeam, which are manager-scoped —
 * "my direct reports"). Here "my team" is employee-scoped: the employee's own peers, i.e. the
 * other direct reports of their current manager. This is unrelated to the ONEHR-106/107
 * manager leaderboard (which does not exist in this codebase).
 */
@Service
@RequiredArgsConstructor
public class AttendanceStatsService {

    private final AttendanceRepository attendanceRepository;
    private final EmployeeManagerHistoryRepository managerHistoryRepository;
    private final EmployeeRepository employeeRepository;
    private final WorkingDayService workingDayService;
    private final ExpectedWorkHoursService expectedWorkHoursService;

    @Transactional(readOnly = true)
    public AttendanceStatsResponse getStats(String actorEmail, LocalDate from, LocalDate to) {
        Employee employee = resolveEmployee(actorEmail);
        UUID employeeId = employee.getUserId();

        List<Attendance> mine = attendanceRepository
                .findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to);

        List<UUID> peerIds = resolvePeerIds(employeeId);
        List<Attendance> teamRows = peerIds.isEmpty()
                ? List.of()
                : attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(peerIds, from, to);

        // Adjusted expected hours (Section 39/45) — same shift + hourly/quarter-day-leave
        // calculation the Penalization Policy engine uses (see ExpectedWorkHoursService), computed
        // once for both buckets rather than independently derived for this display.
        List<UUID> meAndPeerIds = Stream.concat(Stream.of(employeeId), peerIds.stream()).toList();
        Map<UUID, Employee> byId = employeeRepository.findAllByIdWithScheduleDetails(meAndPeerIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Function.identity()));
        Map<UUID, WorkingDaySchedule> schedules =
                workingDayService.computeExpectedWorkingDaysBulk(List.copyOf(byId.values()), from, to);
        Map<String, LeaveRequest> partialHourLeaveByEmployeeDate =
                expectedWorkHoursService.loadPartialHourLeaveByEmployeeDate(meAndPeerIds, from, to);

        return AttendanceStatsResponse.builder()
                .me(aggregate(mine, expectedHoursPerDay(List.of(employeeId), byId, schedules, partialHourLeaveByEmployeeDate)))
                .team(aggregate(teamRows, expectedHoursPerDay(peerIds, byId, schedules, partialHourLeaveByEmployeeDate)))
                .teamSize(peerIds.size())
                .build();
    }

    /** The employee's peers: the other current direct reports of their own current manager. */
    private List<UUID> resolvePeerIds(UUID employeeId) {
        UUID managerId = managerHistoryRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)
                .map(EmployeeManagerHistory::getManagerUserId)
                .orElse(null);
        if (managerId == null) {
            return List.of();
        }
        return managerHistoryRepository.findCurrentDirectReportIds(managerId).stream()
                .filter(id -> !id.equals(employeeId))
                .toList();
    }

    /**
     * Average adjusted expected minutes per working day, across every id in {@code ids} combined —
     * the same "blended across the whole bucket" shape as {@link #aggregate}'s avgHoursPerDay, so
     * the two numbers are directly comparable in the UI. Null when there are no working days, or
     * none of the ids have an assigned shift to compute a figure from.
     */
    private Double expectedHoursPerDay(Collection<UUID> ids, Map<UUID, Employee> byId,
                                        Map<UUID, WorkingDaySchedule> schedules,
                                        Map<String, LeaveRequest> partialHourLeaveByEmployeeDate) {
        long totalMinutes = 0;
        int workingDays = 0;
        for (UUID id : ids) {
            Employee employee = byId.get(id);
            WorkingDaySchedule schedule = schedules.get(id);
            if (employee == null || schedule == null) {
                continue;
            }
            for (LocalDate date : schedule.getWorkingDates()) {
                Long minutes = expectedWorkHoursService.adjustedExpectedMinutes(
                        employee, date, partialHourLeaveByEmployeeDate.get(id + "|" + date));
                if (minutes == null) {
                    continue;
                }
                totalMinutes += minutes;
                workingDays++;
            }
        }
        return workingDays == 0 ? null : Math.round((totalMinutes / 60.0 / workingDays) * 10) / 10.0;
    }

    /**
     * Day-level aggregate across every row in the bucket (total worked minutes / total
     * present-day rows) — not a per-employee-then-averaged figure. Matches AttendanceService's
     * existing convention of aggregating in Java rather than via JPQL AVG.
     */
    private AttendanceStatBucket aggregate(List<Attendance> rows, Double expectedHoursPerDay) {
        List<Attendance> present = rows.stream().filter(r -> r.getCheckInAt() != null).toList();
        int presentDays = present.size();
        if (presentDays == 0) {
            return AttendanceStatBucket.builder().presentDays(0).avgHoursPerDay(null).onTimeArrivalPercent(null)
                    .expectedHoursPerDay(expectedHoursPerDay).build();
        }

        long totalWorkedMinutes = present.stream()
                .mapToLong(r -> r.getWorkedMinutes() != null ? r.getWorkedMinutes() : 0)
                .sum();
        long onTimeCount = present.stream()
                .filter(r -> r.getLateByMinutes() == null || r.getLateByMinutes() == 0)
                .count();

        double avgHoursPerDay = Math.round((totalWorkedMinutes / 60.0 / presentDays) * 10) / 10.0;
        double onTimePercent = Math.round((onTimeCount * 100.0 / presentDays) * 10) / 10.0;

        return AttendanceStatBucket.builder()
                .presentDays(presentDays)
                .avgHoursPerDay(avgHoursPerDay)
                .onTimeArrivalPercent(onTimePercent)
                .expectedHoursPerDay(expectedHoursPerDay)
                .build();
    }

    private Employee resolveEmployee(String actorEmail) {
        return employeeRepository.findByUser_Email(actorEmail)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No employee profile found for this account. Contact HR to complete your profile."));
    }
}
