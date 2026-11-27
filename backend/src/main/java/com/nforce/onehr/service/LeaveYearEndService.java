package com.nforce.onehr.service;

import com.nforce.onehr.dto.LeaveConfigDtos.CarryForwardTransferResponse;
import com.nforce.onehr.dto.LeaveConfigDtos.YearEndRunResult;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveCarryForwardTransfer;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveCarryForwardTransferRepository;
import com.nforce.onehr.repository.LeaveRequestRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static java.math.BigDecimal.ZERO;

/**
 * Leave year-end carry-forward and carried-forward expiry (V310).
 *
 * <p>Everything here is a <b>reconciliation toward a target state</b>, so running it again — after a
 * crash, a failed employee, or simply every night — never double-counts:
 * <ul>
 *   <li><b>Transfer</b>: one {@link LeaveCarryForwardTransfer} per (employee, type, from-year), guarded by
 *       a UNIQUE constraint. Unused = current-year days left + carried-forward still valid at year end
 *       - pending leave still reserved in that year (an approval after year-end then still fits).
 *       Carried = min(cap, unused) when carry-forward is enabled in the employee's leave plan on the last
 *       day of the year (LV-01), else 0 (everything lapses). The
 *       carried days land in the next year's balance as a separate bucket with its own expiry date.</li>
 *   <li><b>Top-up</b>: if a pending request reserved at year-end is later rejected, the freed days are
 *       carried too (up to the cap, never after the bucket expired) — see {@link #refreshTransfers}.
 *       A transfer is never lowered.</li>
 *   <li><b>Expiry</b>: once a bucket's expiry date passes, its days not used and not still claimed by
 *       pending leave dated on/before expiry are marked expired (audited per balance).</li>
 * </ul>
 * Each employee/balance runs in its own transaction: one failure is logged and counted, the rest
 * commit, and the next run picks the failed one up again. Dates are in the business timezone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveYearEndService {

    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveCarryForwardTransferRepository transferRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveBalanceService leaveBalanceService;
    private final LeavePlanRuleService planRuleService;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final PlatformTransactionManager transactionManager;
    private final LeaveJobMonitor jobMonitor;

    /** Nightly: closes the previous leave year (a no-op after the first successful pass) and applies expiries. */
    @Scheduled(cron = "${app.leave.year-end.cron:0 30 0 * * *}", zone = "${app.attendance.zone:Asia/Kolkata}")
    public void scheduledRun() {
        try {
            run(LeaveBalanceService.leaveYearOf(leaveBalanceService.today()) - 1, null);
        } catch (Exception e) {
            log.error("Leave year-end processing failed", e);
        }
    }

    /** Processes {@code fromYear} (must have ended) and every due expiry. {@code actorId} null = scheduler. */
    public YearEndRunResult run(int fromYear, UUID actorId) {
        if (fromYear >= LeaveBalanceService.leaveYearOf(leaveBalanceService.today())) {
            throw new IllegalArgumentException("Leave year " + fromYear + " hasn't ended yet.");
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        int checked = 0, created = 0, expiries = 0, failures = 0;
        Set<UUID> failed = new java.util.LinkedHashSet<>();

        List<LeaveType> types = leaveTypeRepository.findAll().stream().filter(LeaveType::isAnnualBalance).toList();
        Set<String> done = new HashSet<>();
        for (LeaveCarryForwardTransfer t : transferRepository.findByFromYear(fromYear)) {
            done.add(t.getEmployeeUserId() + ":" + t.getLeaveType().getId());
        }
        for (UUID employeeId : employeeRepository.findActiveEmployeeUserIds()) {
            for (LeaveType type : types) {
                if (done.contains(employeeId + ":" + type.getId())) continue;
                checked++;
                try {
                    Boolean ok = tx.execute(s -> createTransfer(employeeId, type.getId(), fromYear));
                    if (Boolean.TRUE.equals(ok)) created++;
                } catch (Exception e) {
                    // includes a UNIQUE violation when another instance got there first — retried next run
                    failures++;
                    failed.add(employeeId);
                    log.warn("Carry-forward failed for employee {} type {} year {}: {}", employeeId, type.getCode(), fromYear, e.getMessage());
                }
            }
        }

        for (LeaveBalance b : leaveBalanceRepository.findWithCarriedForwardExpiredBefore(leaveBalanceService.today())) {
            try {
                Boolean expired = tx.execute(s -> expire(b.getId(), actorId));
                if (Boolean.TRUE.equals(expired)) expiries++;
            } catch (Exception e) {
                failures++;
                failed.add(b.getEmployeeUserId());
                log.warn("Carry-forward expiry failed for balance {}: {}", b.getId(), e.getMessage());
            }
        }

        // YE-07: every run leaves one audit entry (its "last run" status) and alerts Super Admins on failures.
        List<String> failedCodes = jobMonitor.recordRun(LeaveJobMonitor.YEAR_END, "Leave year-end carry-forward", actorId, Map.of(
                "fromYear", fromYear, "balancesChecked", checked, "transfersCreated", created,
                "expiriesUpdated", expiries, "failures", failures), failed);
        return new YearEndRunResult(fromYear, checked, created, expiries, failures, failedCodes);
    }

    /** First transfer for (employee, type, fromYear). False when one already exists. */
    boolean createTransfer(UUID employeeId, UUID typeId, int fromYear) {
        LeaveType type = leaveTypeRepository.findById(typeId).orElseThrow();
        if (transferRepository.findByEmployeeUserIdAndLeaveTypeIdAndFromYear(employeeId, typeId, fromYear).isPresent()) {
            return false;
        }
        LeaveBalance from = leaveBalanceService.ensureBalance(employeeId, type, fromYear);
        BigDecimal unused = unusedAtYearEnd(from);
        // LV-01: the carry-forward rules of the employee's leave plan on the last day of the year.
        Optional<LeavePlanCatalog.TypeRule> rule = closingRule(employeeId, type, fromYear);
        BigDecimal carried = target(rule, unused);
        LocalDate expiresOn = rule.filter(LeavePlanCatalog.TypeRule::carryForwardEnabled)
                .map(LeavePlanCatalog.TypeRule::carryForwardExpiryMonths)
                .map(months -> LeaveBalanceService.yearStart(fromYear + 1).plusMonths(months).minusDays(1))
                .orElse(null);
        LeaveCarryForwardTransfer transfer = transferRepository.save(LeaveCarryForwardTransfer.builder()
                .employeeUserId(employeeId)
                .leaveType(type)
                .fromYear(fromYear)
                .unusedDays(unused)
                .carriedDays(carried)
                .lapsedDays(unused.subtract(carried))
                .expiresOn(expiresOn)
                .build());
        if (carried.signum() > 0) {
            LeaveBalance next = leaveBalanceService.ensureBalance(employeeId, type, fromYear + 1);
            next.setCarriedForwardDays(next.getCarriedForwardDays().add(carried));
            next.setCarriedForwardExpiresOn(expiresOn);
            leaveBalanceRepository.save(next);
        }
        return transfer.getId() != null;
    }

    /**
     * After something freed days in an already-closed leave year (a pending request rejected after
     * year-end, an entitlement adjustment), raise that year's transfer to what it would be now. Runs
     * inside the caller's transaction. No-op for open years, types without carry-forward, years not
     * processed yet (the nightly run will handle them), or a bucket that already expired.
     */
    @Transactional
    public void refreshTransfers(UUID employeeId, LeaveType type, LocalDate start, LocalDate end) {
        int currentYear = LeaveBalanceService.leaveYearOf(leaveBalanceService.today());
        for (int year = LeaveBalanceService.leaveYearOf(start); year <= LeaveBalanceService.leaveYearOf(end); year++) {
            if (year >= currentYear || !type.isAnnualBalance()) continue;
            int fromYear = year;
            Optional<LeavePlanCatalog.TypeRule> rule = closingRule(employeeId, type, fromYear);
            if (rule.isEmpty() || !rule.get().carryForwardEnabled()) continue;
            Optional<LeaveCarryForwardTransfer> existing =
                    transferRepository.findByEmployeeUserIdAndLeaveTypeIdAndFromYear(employeeId, type.getId(), fromYear);
            if (existing.isEmpty()) continue;
            LeaveCarryForwardTransfer t = existing.get();
            if (t.getExpiresOn() != null && leaveBalanceService.today().isAfter(t.getExpiresOn())) continue;
            LeaveBalance from = leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(employeeId, type.getId(), fromYear).orElse(null);
            if (from == null) continue;
            BigDecimal unused = unusedAtYearEnd(from);
            BigDecimal carried = target(rule, unused);
            if (carried.compareTo(t.getCarriedDays()) <= 0) continue;

            BigDecimal delta = carried.subtract(t.getCarriedDays());
            Map<String, Object> before = Map.of("carriedDays", t.getCarriedDays(), "unusedDays", t.getUnusedDays());
            t.setUnusedDays(unused);
            t.setCarriedDays(carried);
            t.setLapsedDays(unused.subtract(carried));
            transferRepository.save(t);
            LeaveBalance next = leaveBalanceService.ensureBalance(employeeId, type, fromYear + 1);
            next.setCarriedForwardDays(next.getCarriedForwardDays().add(delta));
            next.setCarriedForwardExpiresOn(t.getExpiresOn());
            leaveBalanceRepository.save(next);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("employeeUserId", employeeId.toString());
            after.put("leaveTypeCode", type.getCode());
            after.put("fromYear", fromYear);
            after.put("carriedDays", carried);
            after.put("unusedDays", unused);
            AuditService.afterCommit(() -> auditService.log(null, "LEAVE_CARRY_FORWARD_TOPPED_UP", t.getId(),
                    auditSnapshot.toJson(before), auditSnapshot.toJson(after)));
        }
    }

    /** Marks a past-expiry bucket's remaining days expired, keeping what pending leave dated before expiry still claims. */
    boolean expire(UUID balanceId, UUID actorId) {
        LeaveBalance b = leaveBalanceRepository.findById(balanceId).orElseThrow();
        LocalDate cutoff = b.getCarriedForwardExpiresOn();
        if (cutoff == null || !leaveBalanceService.today().isAfter(cutoff)) return false;
        BigDecimal claimed = ZERO;
        for (LeaveRequest r : leaveRequestRepository.findPendingOverlapping(b.getEmployeeUserId(), b.getLeaveType().getId(),
                LeaveBalanceService.yearStart(b.getYear()), cutoff)) {
            claimed = claimed.add(LeaveBalanceService.daysOnOrBefore(r.getStartDate(), r.getEndDate(), r.getTotalDays(), b.getYear(), cutoff,
                    LeaveDayCounts.parse(r.getDayCounts())));
        }
        BigDecimal newlyExpired = LeaveBalanceService.carriedForwardAvailable(b).subtract(claimed);
        if (newlyExpired.signum() <= 0) return false;

        BigDecimal before = b.getCarriedForwardExpiredDays();
        b.setCarriedForwardExpiredDays(before.add(newlyExpired));
        leaveBalanceRepository.save(b);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("employeeUserId", b.getEmployeeUserId().toString());
        after.put("leaveTypeCode", b.getLeaveType().getCode());
        after.put("year", b.getYear());
        after.put("expiresOn", cutoff.toString());
        after.put("expiredDays", b.getCarriedForwardExpiredDays());
        after.put("newlyExpiredDays", newlyExpired);
        AuditService.afterCommit(() -> auditService.log(actorId, "LEAVE_CARRY_FORWARD_EXPIRED", b.getId(),
                auditSnapshot.toJson(Map.of("expiredDays", before)), auditSnapshot.toJson(after)));
        return true;
    }

    /** Current-year days left + carried-forward still valid on the last day of the year - pending leave reserved in it. */
    private BigDecimal unusedAtYearEnd(LeaveBalance b) {
        LeaveBalanceService.AnnualPosition p = leaveBalanceService.position(b, null);
        LocalDate cutoff = b.getCarriedForwardExpiresOn();
        BigDecimal validCf = cutoff == null || cutoff.isAfter(LeaveBalanceService.yearEnd(b.getYear()).minusDays(1))
                ? p.carriedForwardAvailable() : ZERO;
        return p.currentAvailable().add(validCf).subtract(p.pendingDays()).max(ZERO);
    }

    /** The type's rules in the employee's plan on the last day of {@code fromYear}. */
    private Optional<LeavePlanCatalog.TypeRule> closingRule(UUID employeeId, LeaveType type, int fromYear) {
        return planRuleService.ruleFor(employeeId, type.getId(), LeaveBalanceService.yearEnd(fromYear));
    }

    private static BigDecimal target(Optional<LeavePlanCatalog.TypeRule> rule, BigDecimal unused) {
        if (rule.isEmpty() || !rule.get().carryForwardEnabled()) return ZERO;
        BigDecimal max = rule.get().carryForwardMaxDays();
        return max == null ? unused : unused.min(max);
    }

    // ── History ─────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CarryForwardTransferResponse> listTransfers(int fromYear) {
        List<LeaveCarryForwardTransfer> transfers = transferRepository.findByFromYear(fromYear);
        Set<UUID> ids = new HashSet<>();
        transfers.forEach(t -> ids.add(t.getEmployeeUserId()));
        Map<UUID, String> names = new HashMap<>();
        Map<UUID, String> codes = new HashMap<>();
        if (!ids.isEmpty()) {
            employeeRepository.findNamesByUserIds(ids).forEach(r -> names.put((UUID) r[0], (String) r[1]));
            employeeRepository.findCodesByUserIds(ids).forEach(r -> codes.put((UUID) r[0], (String) r[1]));
        }
        return transfers.stream().map(t -> new CarryForwardTransferResponse(t.getId(), t.getEmployeeUserId(),
                names.getOrDefault(t.getEmployeeUserId(), "Unknown"), codes.get(t.getEmployeeUserId()),
                t.getLeaveType().getCode(), t.getLeaveType().getName(), t.getFromYear(), t.getUnusedDays(),
                t.getCarriedDays(), t.getLapsedDays(), t.getExpiresOn(), t.getProcessedAt(), t.getUpdatedAt())).toList();
    }
}
