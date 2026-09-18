package com.nforce.onehr.service;

import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Legacy fallback: every 3rd late arrival in a calendar month costs half a day, deducted from
 * Casual Leave. Shared by every check-in entry point (AttendanceService.checkIn and
 * WebClockInService's self-service remote check-in) so the penalty applies identically no matter
 * how the employee checked in — this used to live only inside AttendanceService, which meant a
 * Web Check-in never triggered it.
 *
 * <p>Skipped entirely once the employee has an applicable configured Penalization Policy with its
 * Late Arrival section enabled for the work date — that engine (see
 * {@link ConfiguredAttendancePolicyEngine}) is authoritative once assigned, and running both would
 * double-penalize the same late arrival. This method is the only backward-compatible path left for
 * an employee with no such policy (or one whose Late Arrival section is disabled).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LatePenaltyService {

    private static final int LATE_PENALTY_EVERY_N = 3;
    private static final BigDecimal LATE_PENALTY_DAYS = new BigDecimal("0.5");
    private static final String LATE_PENALTY_LEAVE_TYPE_CODE = "CASUAL";
    private static final String STATUS_LATE = "LATE";

    private final AttendanceRepository attendanceRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final NotificationService notificationService;
    private final PenalizationPolicyResolutionService penalizationPolicyResolutionService;

    /**
     * Only call for a genuine new late arrival — never for a lunch-break/session resume, since
     * lateness is a once-per-day fact tied to the day's first check-in.
     * A missing Casual Leave balance for the year is logged and skipped rather than thrown — a
     * leave-balance misconfiguration must never block someone from checking in.
     */
    @Transactional
    public void applyIfDue(Employee employee, LocalDate workDate) {
        PenalizationPolicyVersion effectiveConfiguredPolicy =
                penalizationPolicyResolutionService.resolveEffectiveVersionForEmployee(employee, workDate);
        if (effectiveConfiguredPolicy != null && effectiveConfiguredPolicy.isLateArrivalEnabled()) {
            return; // the configured Penalization Policy already covers late arrival for this employee/date
        }

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
}
