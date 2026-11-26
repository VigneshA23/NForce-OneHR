package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.LeaveConfigDtos.AccrualCreditResponse;
import com.nforce.onehr.dto.LeaveConfigDtos.AccrualRunResult;
import com.nforce.onehr.dto.LeaveEmployeeProfile;
import com.nforce.onehr.entity.LeaveAccrualCredit;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveEntitlementPolicy;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveAccrualCreditRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveEntitlementPolicyRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static java.math.BigDecimal.ZERO;

/**
 * Leave crediting (V314). Every regular paid (ANNUAL) type's yearly quota reaches an employee's
 * balance as monthly credits recorded in {@link LeaveAccrualCredit}:
 * <ul>
 *   <li><b>Share of month m</b> = round2(quota·m/12) − round2(quota·(m−1)/12), so the year always adds up
 *       to the quota exactly (15 → 1.25 each month; 10 → 0.83, 0.84, 0.83 …).</li>
 *   <li><b>Eligible months</b>: from the joining month (credited in full, no pro-rating) to December,
 *       stopping after the month of the last working day.</li>
 *   <li><b>MONTHLY</b>: a month is credited once its 1st has arrived.</li>
 *   <li><b>YEARLY</b> (all at once): every eligible month is credited once the first one has arrived. A
 *       part-year total (joiner / leaver) is rounded to the nearest 0.5, the difference on the last month.</li>
 * </ul>
 * A credit is added to {@code leave_balances.total_days} only when its ledger row is inserted, so
 * running this again (nightly, on view, from "Run now", on two instances) never double-credits, and
 * switching YEARLY ↔ MONTHLY mid-year needs nothing special. Employees no longer active, or no
 * longer eligible for the type, get no new credits; what they already have stays.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveAccrualService {

    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveEntitlementPolicyRepository policyRepository;
    private final LeaveAccrualCreditRepository creditRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveEligibilityService eligibilityService;
    private final AttendanceProperties attendanceProperties;
    private final PlatformTransactionManager transactionManager;
    private final LeaveJobMonitor jobMonitor;

    // ── The schedule (pure) ─────────────────────────────────────────────────────────────────────

    public static BigDecimal monthShare(BigDecimal quota, int month) {
        return cumulative(quota, month).subtract(cumulative(quota, month - 1));
    }

    private static BigDecimal cumulative(BigDecimal quota, int months) {
        return quota.multiply(BigDecimal.valueOf(months)).divide(TWELVE, 2, RoundingMode.HALF_UP);
    }

    /** month → days for every eligible month of {@code year}; empty when the employee isn't employed in that year. */
    public static Map<Integer, BigDecimal> schedule(BigDecimal quota, String frequency, LocalDate joining,
                                                    LocalDate lastWorkingDay, int year) {
        Map<Integer, BigDecimal> out = new TreeMap<>();
        if (quota == null || quota.signum() <= 0) return out;
        int first = joining == null || joining.getYear() < year ? 1 : joining.getYear() == year ? joining.getMonthValue() : 13;
        int last = lastWorkingDay == null || lastWorkingDay.getYear() > year ? 12
                : lastWorkingDay.getYear() == year ? lastWorkingDay.getMonthValue() : 0;
        for (int m = first; m <= last; m++) out.put(m, monthShare(quota, m));
        if (LeaveEntitlementPolicy.YEARLY.equals(frequency) && !out.isEmpty() && (first > 1 || last < 12)) {
            BigDecimal total = out.values().stream().reduce(ZERO, BigDecimal::add);
            BigDecimal rounded = total.multiply(TWO).setScale(0, RoundingMode.HALF_UP).divide(TWO, 2, RoundingMode.UNNECESSARY);
            int lastMonth = ((TreeMap<Integer, BigDecimal>) out).lastKey();
            out.put(lastMonth, out.get(lastMonth).add(rounded.subtract(total)));
        }
        return out;
    }

    /** The months of {@code schedule} whose credit date has arrived by {@code today}. */
    public static Set<Integer> dueMonths(Map<Integer, BigDecimal> schedule, String frequency, int year, LocalDate today) {
        Set<Integer> due = new HashSet<>();
        if (schedule.isEmpty()) return due;
        if (LeaveEntitlementPolicy.MONTHLY.equals(frequency)) {
            for (int m : schedule.keySet()) {
                if (!LocalDate.of(year, m, 1).isAfter(today)) due.add(m);
            }
        } else if (!LocalDate.of(year, ((TreeMap<Integer, BigDecimal>) schedule).firstKey(), 1).isAfter(today)) {
            due.addAll(schedule.keySet());
        }
        return due;
    }

    // ── Per balance ─────────────────────────────────────────────────────────────────────────────

    public LocalDate today() {
        return LocalDate.now(ZoneId.of(attendanceProperties.getZone()));
    }

    public Optional<LeaveEntitlementPolicy> policyFor(LeaveType type, int year) {
        return policyRepository.findFirstByLeaveTypeIdAndEffectiveYearLessThanEqualOrderByEffectiveYearDesc(type.getId(), year);
    }

    public static String frequencyOf(Optional<LeaveEntitlementPolicy> policy) {
        return policy.map(LeaveEntitlementPolicy::getCreditFrequency).orElse(LeaveEntitlementPolicy.YEARLY);
    }

    /**
     * Credits every due, not-yet-credited month of (employee, type, year) and returns the days added.
     * Creates the balance row (0 credited) if needed. Nothing for an employee no longer employed or
     * not eligible for the type, for inactive types, or for anything but regular paid leave.
     */
    @Transactional
    public BigDecimal accrue(UUID employeeUserId, LeaveType type, int year) {
        if (!type.isAnnualBalance() || !type.isActive()) return ZERO;
        LeaveEmployeeProfile profile = employeeRepository.findLeaveProfile(employeeUserId).orElse(null);
        if (profile == null || !profile.employed()) return ZERO;
        if (!eligibilityService.rulesFor(type.getId()).matches(employeeUserId, profile)) return ZERO;
        Optional<LeaveEntitlementPolicy> policy = policyFor(type, year);
        String frequency = frequencyOf(policy);
        Map<Integer, BigDecimal> schedule = schedule(policy.map(LeaveEntitlementPolicy::getDays).orElse(ZERO), frequency,
                profile.joiningDate(), profile.lastWorkingDay(), year);
        Set<Integer> due = dueMonths(schedule, frequency, year, today());
        if (due.isEmpty()) return ZERO;
        creditRepository.findByEmployeeUserIdAndLeaveTypeIdAndYearOrderByMonthAsc(employeeUserId, type.getId(), year)
                .forEach(c -> due.remove(c.getMonth()));
        return credit(employeeUserId, type, year, schedule, due);
    }

    private BigDecimal credit(UUID employeeUserId, LeaveType type, int year, Map<Integer, BigDecimal> schedule, Set<Integer> months) {
        if (months.isEmpty()) return ZERO;
        leaveBalanceRepository.insertIfAbsent(employeeUserId, type.getId(), year, ZERO);
        LeaveBalance b = leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(employeeUserId, type.getId(), year)
                .orElseThrow();
        BigDecimal added = ZERO;
        for (int m : sortedMonths(months)) {
            BigDecimal days = schedule.get(m);
            if (days == null || days.signum() == 0) continue;
            if (creditRepository.insertIfAbsent(UUID.randomUUID(), employeeUserId, type.getId(), year, m, days) == 1) {
                added = added.add(days);
            }
        }
        if (added.signum() != 0) {
            b.setTotalDays(b.getTotalDays().add(added));
            leaveBalanceRepository.save(b);
        }
        return added;
    }

    /** Credits still to come this year for a balance, and the next one. */
    public record Projection(String frequency, BigDecimal upcomingDays, LocalDate nextCreditDate, BigDecimal nextCreditDays) {
        public static final Projection NONE = new Projection(LeaveEntitlementPolicy.YEARLY, ZERO, null, null);
    }

    /**
     * What (employee, type, year) will still be credited under the policy in force, for an employee who
     * is employed and eligible — the advance an employee may take, and the "next credit" hint.
     */
    @Transactional(readOnly = true)
    public Projection project(UUID employeeUserId, LeaveType type, int year) {
        if (!type.isAnnualBalance()) return Projection.NONE;
        Optional<LeaveEntitlementPolicy> policy = policyFor(type, year);
        String frequency = frequencyOf(policy);
        if (!type.isActive()) return new Projection(frequency, ZERO, null, null);
        LeaveEmployeeProfile profile = employeeRepository.findLeaveProfile(employeeUserId).orElse(null);
        if (profile == null || !profile.employed()
                || !eligibilityService.rulesFor(type.getId()).matches(employeeUserId, profile)) {
            return new Projection(frequency, ZERO, null, null);
        }
        Map<Integer, BigDecimal> schedule = schedule(policy.map(LeaveEntitlementPolicy::getDays).orElse(ZERO), frequency,
                profile.joiningDate(), profile.lastWorkingDay(), year);
        creditRepository.findByEmployeeUserIdAndLeaveTypeIdAndYearOrderByMonthAsc(employeeUserId, type.getId(), year)
                .forEach(c -> schedule.remove(c.getMonth()));
        BigDecimal upcoming = schedule.values().stream().reduce(ZERO, BigDecimal::add);
        if (schedule.isEmpty()) return new Projection(frequency, ZERO, null, null);
        int next = ((TreeMap<Integer, BigDecimal>) schedule).firstKey();
        BigDecimal nextDays = LeaveEntitlementPolicy.MONTHLY.equals(frequency) ? schedule.get(next) : upcoming;
        return new Projection(frequency, upcoming, LocalDate.of(year, next, 1), nextDays);
    }

    @Transactional(readOnly = true)
    public List<AccrualCreditResponse> history(UUID employeeUserId, UUID leaveTypeId, int year) {
        return creditRepository.findByEmployeeUserIdAndLeaveTypeIdAndYearOrderByMonthAsc(employeeUserId, leaveTypeId, year).stream()
                .map(c -> new AccrualCreditResponse(c.getYear(), c.getMonth(), c.getDays(), c.getSource(), c.getCreditedAt()))
                .toList();
    }

    /**
     * "Update existing balances" after an entitlement change: the already-credited months of every
     * balance of {@code type} in [fromYear, untilYear) are re-credited at the new policy's shares.
     * Normally the difference is added to the balance (manual adjustments survive). A balance from
     * before crediting existed (MIGRATION months) has no per-month figures, so its credited total is
     * set to the new shares outright. Returns the number of balances changed.
     */
    @Transactional
    public int recreditExisting(LeaveType type, int fromYear, int untilYear) {
        int changed = 0;
        for (LeaveBalance b : leaveBalanceRepository.findByLeaveTypeIdAndYearGreaterThanEqual(type.getId(), fromYear)) {
            if (b.getYear() >= untilYear) continue;
            LeaveEmployeeProfile profile = employeeRepository.findLeaveProfile(b.getEmployeeUserId()).orElse(null);
            Optional<LeaveEntitlementPolicy> policy = policyFor(type, b.getYear());
            Map<Integer, BigDecimal> schedule = schedule(policy.map(LeaveEntitlementPolicy::getDays).orElse(ZERO), frequencyOf(policy),
                    profile != null ? profile.joiningDate() : null, profile != null ? profile.lastWorkingDay() : null, b.getYear());
            List<LeaveAccrualCredit> rows = creditRepository.findByEmployeeUserIdAndLeaveTypeIdAndYearOrderByMonthAsc(
                    b.getEmployeeUserId(), type.getId(), b.getYear());
            if (rows.isEmpty()) continue;
            boolean migrated = rows.stream().anyMatch(r -> LeaveAccrualCredit.SOURCE_MIGRATION.equals(r.getSource()));
            BigDecimal before = b.getTotalDays();
            BigDecimal newCredited = ZERO, delta = ZERO;
            for (LeaveAccrualCredit r : rows) {
                BigDecimal share = schedule.getOrDefault(r.getMonth(), ZERO);
                newCredited = newCredited.add(share);
                delta = delta.add(share.subtract(r.getDays()));
                r.setDays(share);
                r.setSource(LeaveAccrualCredit.SOURCE_ACCRUAL);
            }
            creditRepository.saveAll(rows);
            b.setTotalDays(migrated ? newCredited : b.getTotalDays().add(delta));
            if (b.getTotalDays().compareTo(before) != 0) {
                leaveBalanceRepository.save(b);
                changed++;
            }
        }
        return changed;
    }

    // ── Batch ───────────────────────────────────────────────────────────────────────────────────

    /** Nightly, so a missed run catches up the next night. In January it also finishes the previous year. */
    @Scheduled(cron = "${app.leave.accrual.cron:0 15 0 * * *}", zone = "${app.attendance.zone:Asia/Kolkata}")
    public void scheduledRun() {
        try {
            run(null);
        } catch (Exception e) {
            log.error("Leave crediting failed", e);
        }
    }

    /** Credits everything due for every active, eligible employee. {@code actorId} null = scheduler. */
    public AccrualRunResult run(UUID actorId) {
        LocalDate today = today();
        int year = LeaveBalanceService.leaveYearOf(today);
        Set<UUID> failed = new LinkedHashSet<>();
        AccrualRunResult result = runYear(year, today, failed);
        if (today.getMonthValue() == 1) {
            AccrualRunResult previous = runYear(year - 1, today, failed);
            result = new AccrualRunResult(year, result.employeesChecked() + previous.employeesChecked(),
                    result.creditsAdded() + previous.creditsAdded(), result.failures() + previous.failures(), List.of());
        }
        // YE-07: every run leaves one audit entry (its "last run" status) and alerts Super Admins on failures.
        List<String> failedCodes = jobMonitor.recordRun(LeaveJobMonitor.ACCRUAL, "Leave crediting", actorId, Map.of(
                "year", year, "employeesChecked", result.employeesChecked(),
                "creditsAdded", result.creditsAdded(), "failures", result.failures()), failed);
        return new AccrualRunResult(result.year(), result.employeesChecked(), result.creditsAdded(), result.failures(), failedCodes);
    }

    private AccrualRunResult runYear(int year, LocalDate today, Set<UUID> failed) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        List<LeaveEmployeeProfile> employees = employeeRepository.findLeaveProfiles().stream()
                .filter(LeaveEmployeeProfile::employed).toList();
        int checked = 0, added = 0, failures = 0;
        for (LeaveType type : leaveTypeRepository.findByActiveTrueOrderByNameAsc()) {
            if (!type.isAnnualBalance()) continue;
            Optional<LeaveEntitlementPolicy> policy = policyFor(type, year);
            String frequency = frequencyOf(policy);
            BigDecimal quota = policy.map(LeaveEntitlementPolicy::getDays).orElse(ZERO);
            LeaveEligibilityService.Rules rules = eligibilityService.rulesFor(type.getId());
            Map<UUID, Set<Integer>> credited = new HashMap<>();
            for (Object[] row : creditRepository.findCreditedMonths(type.getId(), year)) {
                credited.computeIfAbsent((UUID) row[0], k -> new HashSet<>()).add((Integer) row[1]);
            }
            for (LeaveEmployeeProfile p : employees) {
                if (!rules.matches(p.userId(), p)) continue;
                Map<Integer, BigDecimal> schedule = schedule(quota, frequency, p.joiningDate(), p.lastWorkingDay(), year);
                Set<Integer> due = dueMonths(schedule, frequency, year, today);
                due.removeAll(credited.getOrDefault(p.userId(), Set.of()));
                due.removeIf(m -> schedule.get(m).signum() == 0);
                if (due.isEmpty()) continue;
                checked++;
                try {
                    BigDecimal days = tx.execute(s -> credit(p.userId(), type, year, schedule, due));
                    if (days != null && days.signum() != 0) added++;
                } catch (Exception e) {
                    failures++;
                    failed.add(p.userId());
                    log.warn("Leave crediting failed for employee {} type {} year {}: {}", p.userId(), type.getCode(), year, e.getMessage());
                }
            }
        }
        return new AccrualRunResult(year, checked, added, failures, List.of());
    }

    static List<Integer> sortedMonths(Set<Integer> months) {
        List<Integer> out = new ArrayList<>(months);
        out.sort(Integer::compare);
        return out;
    }
}
