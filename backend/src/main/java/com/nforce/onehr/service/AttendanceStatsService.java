package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.AttendanceStatBucket;
import com.nforce.onehr.dto.attendance.AttendanceStatsResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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

        return AttendanceStatsResponse.builder()
                .me(aggregate(mine))
                .team(aggregate(teamRows))
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
     * Day-level aggregate across every row in the bucket (total worked minutes / total
     * present-day rows) — not a per-employee-then-averaged figure. Matches AttendanceService's
     * existing convention of aggregating in Java rather than via JPQL AVG.
     */
    private AttendanceStatBucket aggregate(List<Attendance> rows) {
        List<Attendance> present = rows.stream().filter(r -> r.getCheckInAt() != null).toList();
        int presentDays = present.size();
        if (presentDays == 0) {
            return AttendanceStatBucket.builder().presentDays(0).avgHoursPerDay(null).onTimeArrivalPercent(null).build();
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
                .build();
    }

    private Employee resolveEmployee(String actorEmail) {
        return employeeRepository.findByUser_Email(actorEmail)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No employee profile found for this account. Contact HR to complete your profile."));
    }
}
