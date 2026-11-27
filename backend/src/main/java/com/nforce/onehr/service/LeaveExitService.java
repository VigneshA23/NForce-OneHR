package com.nforce.onehr.service;

import com.nforce.onehr.dto.LeaveEmployeeProfile;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveExitSettlement;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveExitSettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.math.BigDecimal.ZERO;

/**
 * LV-04 (ONEHR-890): the leave position of an employee who leaves. For the leave year of the last working day,
 * credits are recomputed up to that day by the plan's proration ({@link LeaveAccrualService#reconcile} takes back
 * the months after it), then per regular paid type: available = credited − used + carried-forward still usable.
 * A positive remainder can be encashed; a negative one is leave taken beyond the entitlement, flagged for
 * recovery in the final settlement (payroll's exit proration and Joiners &amp; Exits read it).
 *
 * <p>The last working day is entered on the employee record; the app has no exit screen yet. So settlements are
 * refreshed nightly for every employee whose last working day lies within {@link #REFRESH_WINDOW_DAYS} days
 * (before or after today), and on demand (HR, payroll's proration of an exit cycle).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveExitService {

    /** Leave approved or rejected around an exit keeps changing the figures for a while after it. */
    static final int REFRESH_WINDOW_DAYS = 60;

    private final EmployeeRepository employeeRepository;
    private final LeaveBalanceRepository balanceRepository;
    private final LeaveExitSettlementRepository settlementRepository;
    private final LeaveAccrualService accrualService;
    private final PlatformTransactionManager transactionManager;

    /**
     * Recomputes and stores the settlement of one employee; empty (and any old settlement removed) when they have
     * no last working day.
     */
    @Transactional
    public List<LeaveExitSettlement> settle(UUID employeeUserId) {
        LeaveEmployeeProfile profile = employeeRepository.findLeaveProfile(employeeUserId).orElse(null);
        List<LeaveExitSettlement> old = settlementRepository.findByEmployeeUserId(employeeUserId);
        if (profile == null || profile.lastWorkingDay() == null) {
            // The exit was withdrawn: the year's credits taken back up to the old last working day are restored.
            old.stream().map(LeaveExitSettlement::getYear).distinct().min(Integer::compare).ifPresent(year ->
                    accrualService.reconcile(List.of(employeeUserId), LeaveBalanceService.yearStart(year), false));
            settlementRepository.deleteAll(old);
            return List.of();
        }
        LocalDate lwd = profile.lastWorkingDay();
        int year = LeaveBalanceService.leaveYearOf(lwd);
        // Credits up to the last working day (months after it are taken back, the exit month prorated).
        accrualService.reconcile(List.of(employeeUserId), LeaveBalanceService.yearStart(year), false);

        Map<UUID, LeaveExitSettlement> byType = new HashMap<>();
        for (LeaveExitSettlement s : old) {
            if (s.getYear() == year) {
                byType.put(s.getLeaveTypeId(), s);
            } else { // the last working day moved to another year: restore that year's credits
                if (s.getYear() < year) accrualService.reconcile(List.of(employeeUserId), LeaveBalanceService.yearStart(s.getYear()), false);
                settlementRepository.delete(s);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        for (LeaveBalance b : balanceRepository.findByEmployeeUserIdAndYear(employeeUserId, year)) {
            if (!b.getLeaveType().isAnnualBalance()) continue;
            BigDecimal cf = LeaveBalanceService.carriedForwardAvailable(b);
            BigDecimal available = b.getTotalDays().subtract(b.getUsedDays()).add(cf);
            LeaveExitSettlement s = byType.computeIfAbsent(b.getLeaveType().getId(), id -> LeaveExitSettlement.builder()
                    .employeeUserId(employeeUserId).leaveTypeId(id).year(year).build());
            s.setLastWorkingDay(lwd);
            s.setEntitledDays(b.getTotalDays());
            s.setCarriedForwardDays(cf);
            s.setUsedDays(b.getUsedDays());
            s.setEncashableDays(available.max(ZERO));
            s.setRecoveryDays(available.negate().max(ZERO));
            s.setComputedAt(now);
        }
        return settlementRepository.saveAll(byType.values());
    }

    /** Days that can be encashed on exit, across regular paid types (0 when there is no last working day). */
    @Transactional
    public BigDecimal encashableDays(UUID employeeUserId) {
        return settle(employeeUserId).stream().map(LeaveExitSettlement::getEncashableDays).reduce(ZERO, BigDecimal::add);
    }

    /** Stored settlements of these employees (no recompute) — for read-only screens. */
    @Transactional(readOnly = true)
    public Map<UUID, List<LeaveExitSettlement>> stored(java.util.Collection<UUID> employeeUserIds) {
        Map<UUID, List<LeaveExitSettlement>> out = new HashMap<>();
        if (employeeUserIds.isEmpty()) return out;
        for (LeaveExitSettlement s : settlementRepository.findByEmployeeUserIdIn(employeeUserIds)) {
            out.computeIfAbsent(s.getEmployeeUserId(), k -> new java.util.ArrayList<>()).add(s);
        }
        return out;
    }

    /** Stored settlements with a last working day in [from, to] (no recompute). */
    @Transactional(readOnly = true)
    public List<LeaveExitSettlement> storedBetween(LocalDate from, LocalDate to) {
        return settlementRepository.findByLastWorkingDayBetweenOrderByLastWorkingDayAsc(from, to);
    }

    /** Nightly, after crediting: every employee whose last working day is within the window. */
    @Scheduled(cron = "${app.leave.exit.cron:0 45 0 * * *}", zone = "${app.attendance.zone:Asia/Kolkata}")
    public void refreshRecentExits() {
        LocalDate today = accrualService.today();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        int done = 0, failed = 0;
        for (LeaveEmployeeProfile p : employeeRepository.findLeaveProfiles()) {
            LocalDate lwd = p.lastWorkingDay();
            if (lwd == null || lwd.isBefore(today.minusDays(REFRESH_WINDOW_DAYS)) || lwd.isAfter(today.plusDays(REFRESH_WINDOW_DAYS))) continue;
            try {
                tx.executeWithoutResult(s -> settle(p.userId()));
                done++;
            } catch (Exception e) {
                failed++;
                log.warn("Leave exit settlement failed for employee {}: {}", p.userId(), e.getMessage());
            }
        }
        if (done + failed > 0) log.info("Leave exit settlements refreshed: {} done, {} failed", done, failed);
    }
}
