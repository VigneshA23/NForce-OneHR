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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.IntFunction;

import static java.math.BigDecimal.ZERO;

/**
 * Leave crediting (V314; per leave plan since LV-01). Every regular paid (ANNUAL) type's yearly quota
 * reaches an employee's balance as monthly credits recorded in {@link LeaveAccrualCredit}. The quota and
 * frequency of each month come from the employee's leave plan in effect that month
 * ({@link LeavePlanCatalog}), so a mid-year plan switch or plan version is prorated by month:
 * <ul>
 *   <li><b>Share of month m</b> = round2(quota·m/12) − round2(quota·(m−1)/12), so the year always adds up
 *       to the quota exactly (15 → 1.25 each month; 10 → 0.83, 0.84, 0.83 …).</li>
 *   <li><b>Eligible months</b>: from the joining month (credited in full, no pro-rating) to December,
 *       stopping after the month of the last working day. A month whose plan doesn't include the type
 *       (or whose "applies to" rules exclude the employee) is not credited.</li>
 *   <li><b>MONTHLY</b>: a month is credited once its 1st has arrived.</li>
 *   <li><b>YEARLY</b> (all at once): a run of months under one plan and quota is credited once its first
 *       month has arrived. A part-year run (joiner, leaver, plan switch) is rounded to the nearest 0.5,
 *       the difference on its last month.</li>
 * </ul>
 * A credit is added to {@code leave_balances.total_days} only when its ledger row is inserted, so
 * running this again (nightly, on view, from "Run now", on two instances) never double-credits.
 * Employees no longer active, or no longer eligible for the type, get no new credits; what they already
 * have stays. Plan changes re-credit months already credited through {@link #reconcile}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveAccrualService {

    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveAccrualCreditRepository creditRepository;
    private final EmployeeRepository employeeRepository;
    private final LeavePlanRuleService planRuleService;
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

    /** What governs one month's credit: the plan (null in single-rule calls), the yearly quota and the frequency. */
    public record MonthRule(UUID planId, BigDecimal quota, String frequency) {
        boolean yearly() {
            return !LeaveEntitlementPolicy.MONTHLY.equals(frequency);
        }

        boolean sameRun(MonthRule o) {
            return o != null && Objects.equals(planId, o.planId) && yearly() == o.yearly() && quota.compareTo(o.quota) == 0;
        }
    }

    /** month → days for every eligible month of {@code year} under one quota and frequency. */
    public static Map<Integer, BigDecimal> schedule(BigDecimal quota, String frequency, LocalDate joining,
                                                    LocalDate lastWorkingDay, int year) {
        MonthRule r = quota == null ? null : new MonthRule(null, quota, frequency);
        return schedule(m -> r, joining, lastWorkingDay, year);
    }

    /**
     * month → days for every eligible month of {@code year}: from the joining month to the month of the
     * last working day, each month's share from that month's rule; a month with no rule or a 0 quota is
     * not credited. A YEARLY run (see {@link #runs}) that isn't the whole year is rounded to the nearest
     * 0.5, the difference on its last month.
     */
    public static Map<Integer, BigDecimal> schedule(IntFunction<MonthRule> ruleOfMonth, LocalDate joining,
                                                    LocalDate lastWorkingDay, int year) {
        TreeMap<Integer, BigDecimal> out = new TreeMap<>();
        int first = joining == null || joining.getYear() < year ? 1 : joining.getYear() == year ? joining.getMonthValue() : 13;
        int last = lastWorkingDay == null || lastWorkingDay.getYear() > year ? 12
                : lastWorkingDay.getYear() == year ? lastWorkingDay.getMonthValue() : 0;
        Map<Integer, MonthRule> rules = new HashMap<>();
        for (int m = first; m <= last; m++) {
            MonthRule r = ruleOfMonth.apply(m);
            if (r == null || r.quota() == null || r.quota().signum() <= 0) continue;
            rules.put(m, r);
            out.put(m, monthShare(r.quota(), m));
        }
        for (List<Integer> run : runs(out.keySet(), rules)) {
            if (!rules.get(run.get(0)).yearly() || (run.get(0) == 1 && run.get(run.size() - 1) == 12)) continue;
            BigDecimal total = run.stream().map(out::get).reduce(ZERO, BigDecimal::add);
            BigDecimal rounded = total.multiply(TWO).setScale(0, RoundingMode.HALF_UP).divide(TWO, 2, RoundingMode.UNNECESSARY);
            int lastMonth = run.get(run.size() - 1);
            out.put(lastMonth, out.get(lastMonth).add(rounded.subtract(total)));
        }
        return out;
    }

    /** Runs of consecutive months under the same plan, frequency and quota. */
    private static List<List<Integer>> runs(Collection<Integer> months, Map<Integer, MonthRule> rules) {
        List<List<Integer>> out = new ArrayList<>();
        List<Integer> current = null;
        Integer prev = null;
        for (int m : new java.util.TreeSet<>(months)) {
            if (current == null || m != prev + 1 || !rules.get(m).sameRun(rules.get(prev))) {
                current = new ArrayList<>();
                out.add(current);
            }
            current.add(m);
            prev = m;
        }
        return out;
    }

    /** The months of {@code schedule} whose credit date has arrived by {@code today} (one quota and frequency). */
    public static Set<Integer> dueMonths(Map<Integer, BigDecimal> schedule, String frequency, int year, LocalDate today) {
        MonthRule r = new MonthRule(null, BigDecimal.ONE, frequency);
        return dueMonths(schedule, m -> r, year, today);
    }

    /** MONTHLY months are due on their 1st; a YEARLY run's months once the first month of the run has arrived. */
    public static Set<Integer> dueMonths(Map<Integer, BigDecimal> schedule, IntFunction<MonthRule> ruleOfMonth, int year,
                                         LocalDate today) {
        Set<Integer> due = new HashSet<>();
        if (schedule.isEmpty()) return due;
        Map<Integer, MonthRule> rules = new HashMap<>();
        for (int m : schedule.keySet()) rules.put(m, ruleOfMonth.apply(m));
        for (List<Integer> run : runs(schedule.keySet(), rules)) {
            boolean yearly = rules.get(run.get(0)).yearly();
            for (int m : run) {
                if (!LocalDate.of(year, yearly ? run.get(0) : m, 1).isAfter(today)) due.add(m);
            }
        }
        return due;
    }

    /**
     * The rule of {@code type} for month {@code m} of {@code year}: the employee's plan on the 1st (on the
     * joining date in the joining month). {@code requireEligible} also applies the type's "applies to" rules;
     * re-crediting after a plan change doesn't, so losing eligibility alone never takes credits back.
     */
    static MonthRule monthRule(LeavePlanCatalog catalog, UUID employeeUserId, LeaveEmployeeProfile profile, LeaveType type,
                               int year, int m, boolean requireEligible) {
        LocalDate date = LocalDate.of(year, m, 1);
        LocalDate joining = profile != null ? profile.joiningDate() : null;
        if (joining != null && joining.getYear() == year && joining.getMonthValue() == m && joining.isAfter(date)) date = joining;
        Optional<LeavePlanCatalog.TypeRule> rule = requireEligible
                ? LeaveEligibilityService.applicableRule(catalog, employeeUserId, profile, type.getId(), date)
                : catalog.ruleFor(employeeUserId, type.getId(), date);
        return rule.map(r -> new MonthRule(r.planId(), r.days() == null ? ZERO : r.days(), r.creditFrequency())).orElse(null);
    }

    // ── Per balance ─────────────────────────────────────────────────────────────────────────────

    public LocalDate today() {
        return LocalDate.now(ZoneId.of(attendanceProperties.getZone()));
    }

    /**
     * Credits every due, not-yet-credited month of (employee, type, year) and returns the days added.
     * Creates the balance row (0 credited) if needed. Nothing for an employee no longer employed, for
     * inactive types, or for anything but regular paid leave.
     */
    @Transactional
    public BigDecimal accrue(UUID employeeUserId, LeaveType type, int year) {
        if (!type.isAnnualBalance() || !type.isActive()) return ZERO;
        LeaveEmployeeProfile profile = employeeRepository.findLeaveProfile(employeeUserId).orElse(null);
        if (profile == null || !profile.employed()) return ZERO;
        LeavePlanCatalog catalog = planRuleService.catalog(List.of(employeeUserId));
        IntFunction<MonthRule> rules = m -> monthRule(catalog, employeeUserId, profile, type, year, m, true);
        Map<Integer, BigDecimal> schedule = schedule(rules, profile.joiningDate(), profile.lastWorkingDay(), year);
        Set<Integer> due = dueMonths(schedule, rules, year, today());
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
     * What (employee, type, year) will still be credited under the plan rules in force, for an employee who
     * is employed and eligible — the advance an employee may take, and the "next credit" hint.
     */
    @Transactional(readOnly = true)
    public Projection project(UUID employeeUserId, LeaveType type, int year) {
        if (!type.isAnnualBalance()) return Projection.NONE;
        LeavePlanCatalog catalog = planRuleService.catalog(List.of(employeeUserId));
        String frequency = frequencyShownFor(catalog, employeeUserId, type, year);
        if (!type.isActive()) return new Projection(frequency, ZERO, null, null);
        LeaveEmployeeProfile profile = employeeRepository.findLeaveProfile(employeeUserId).orElse(null);
        if (profile == null || !profile.employed()) return new Projection(frequency, ZERO, null, null);
        IntFunction<MonthRule> rules = m -> monthRule(catalog, employeeUserId, profile, type, year, m, true);
        Map<Integer, BigDecimal> full = schedule(rules, profile.joiningDate(), profile.lastWorkingDay(), year);
        Map<Integer, BigDecimal> schedule = new TreeMap<>(full);
        creditRepository.findByEmployeeUserIdAndLeaveTypeIdAndYearOrderByMonthAsc(employeeUserId, type.getId(), year)
                .forEach(c -> schedule.remove(c.getMonth()));
        if (schedule.isEmpty()) return new Projection(frequency, ZERO, null, null);
        BigDecimal upcoming = schedule.values().stream().reduce(ZERO, BigDecimal::add);
        int next = ((TreeMap<Integer, BigDecimal>) schedule).firstKey();
        MonthRule nextRule = rules.apply(next);
        BigDecimal nextDays = schedule.get(next);
        if (nextRule != null && nextRule.yearly()) {
            // the rest of the run starting at `next` arrives together
            for (int m = next + 1; m <= 12 && schedule.containsKey(m) && nextRule.sameRun(rules.apply(m)); m++) {
                nextDays = nextDays.add(schedule.get(m));
            }
        }
        return new Projection(nextRule != null ? nextRule.frequency() : frequency, upcoming, LocalDate.of(year, next, 1), nextDays);
    }

    /** The frequency to show for a year: this month's rule for the current year, January's for a later year, December's for an earlier one. */
    private String frequencyShownFor(LeavePlanCatalog catalog, UUID employeeUserId, LeaveType type, int year) {
        LocalDate today = today();
        LocalDate date = year == today.getYear() ? today : year > today.getYear() ? LocalDate.of(year, 1, 1) : LocalDate.of(year, 12, 31);
        return catalog.ruleFor(employeeUserId, type.getId(), date)
                .map(LeavePlanCatalog.TypeRule::creditFrequency).orElse(LeaveEntitlementPolicy.YEARLY);
    }

    @Transactional(readOnly = true)
    public List<AccrualCreditResponse> history(UUID employeeUserId, UUID leaveTypeId, int year) {
        return creditRepository.findByEmployeeUserIdAndLeaveTypeIdAndYearOrderByMonthAsc(employeeUserId, leaveTypeId, year).stream()
                .map(c -> new AccrualCreditResponse(c.getYear(), c.getMonth(), c.getDays(), c.getSource(), c.getCreditedAt()))
                .toList();
    }

    /**
     * After a plan change (LV-01) — an employee switched plan, a plan version was added or edited — the
     * months already credited from {@code from}'s month on are re-credited at what the plans now say; the
     * difference is added to the balance, so manual adjustments survive. This is what prorates a mid-year
     * plan switch: months under the old plan keep its shares, the rest get the new plan's, and a month
     * whose plan no longer includes the type is taken back. Losing "applies to" eligibility alone never
     * takes credits back.
     *
     * <p>A balance from before crediting existed (V314 MIGRATION rows, no per-month figures) is re-credited
     * only when {@code includeMigrated} and the change covers its whole year; its credited total is then set
     * to the new shares outright (the "update existing balances" option).
     *
     * @param employeeUserIds the employees affected, or null for everyone
     * @return the number of balance rows changed
     */
    @Transactional
    public int reconcile(Collection<UUID> employeeUserIds, LocalDate from, boolean includeMigrated) {
        int fromYear = LeaveBalanceService.leaveYearOf(from);
        LeavePlanCatalog catalog = employeeUserIds == null ? planRuleService.catalogForAll() : planRuleService.catalog(employeeUserIds);
        List<LeaveBalance> balances = employeeUserIds == null ? leaveBalanceRepository.findByYearGreaterThanEqual(fromYear)
                : employeeUserIds.isEmpty() ? List.of()
                : leaveBalanceRepository.findByEmployeeUserIdInAndYearGreaterThanEqual(employeeUserIds, fromYear);
        Map<UUID, LeaveEmployeeProfile> profiles = new HashMap<>();
        if (!balances.isEmpty() && employeeUserIds != null && employeeUserIds.size() <= 50) {
            for (UUID id : employeeUserIds) employeeRepository.findLeaveProfile(id).ifPresent(p -> profiles.put(p.userId(), p));
        } else if (!balances.isEmpty()) {
            employeeRepository.findLeaveProfiles().forEach(p -> profiles.put(p.userId(), p));
        }
        int changed = 0;
        for (LeaveBalance b : balances) {
            LeaveType type = b.getLeaveType();
            if (!type.isAnnualBalance()) continue;
            LeaveEmployeeProfile profile = profiles.get(b.getEmployeeUserId());
            int year = b.getYear();
            Map<Integer, BigDecimal> schedule = schedule(m -> monthRule(catalog, b.getEmployeeUserId(), profile, type, year, m, false),
                    profile != null ? profile.joiningDate() : null, profile != null ? profile.lastWorkingDay() : null, year);
            List<LeaveAccrualCredit> rows = creditRepository.findByEmployeeUserIdAndLeaveTypeIdAndYearOrderByMonthAsc(
                    b.getEmployeeUserId(), type.getId(), year);
            if (rows.isEmpty()) continue;
            int fromMonth = year == fromYear ? from.getMonthValue() : 1;
            boolean migrated = rows.stream().anyMatch(r -> LeaveAccrualCredit.SOURCE_MIGRATION.equals(r.getSource()));
            BigDecimal before = b.getTotalDays();
            if (migrated) {
                if (!includeMigrated || fromMonth != 1) continue;
                BigDecimal newCredited = ZERO;
                for (LeaveAccrualCredit r : rows) {
                    BigDecimal share = schedule.getOrDefault(r.getMonth(), ZERO);
                    newCredited = newCredited.add(share);
                    r.setDays(share);
                    r.setSource(LeaveAccrualCredit.SOURCE_ACCRUAL);
                }
                creditRepository.saveAll(rows);
                b.setTotalDays(newCredited);
            } else {
                BigDecimal delta = ZERO;
                List<LeaveAccrualCredit> touched = new ArrayList<>();
                for (LeaveAccrualCredit r : rows) {
                    if (r.getMonth() < fromMonth) continue;
                    BigDecimal share = schedule.getOrDefault(r.getMonth(), ZERO);
                    if (share.compareTo(r.getDays()) == 0) continue;
                    delta = delta.add(share.subtract(r.getDays()));
                    r.setDays(share);
                    touched.add(r);
                }
                if (touched.isEmpty()) continue;
                creditRepository.saveAll(touched);
                b.setTotalDays(b.getTotalDays().add(delta));
            }
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
        LeavePlanCatalog catalog = planRuleService.catalogForAll();
        int checked = 0, added = 0, failures = 0;
        for (LeaveType type : leaveTypeRepository.findByActiveTrueOrderByNameAsc()) {
            if (!type.isAnnualBalance()) continue;
            Map<UUID, Set<Integer>> credited = new HashMap<>();
            for (Object[] row : creditRepository.findCreditedMonths(type.getId(), year)) {
                credited.computeIfAbsent((UUID) row[0], k -> new HashSet<>()).add((Integer) row[1]);
            }
            for (LeaveEmployeeProfile p : employees) {
                IntFunction<MonthRule> rules = m -> monthRule(catalog, p.userId(), p, type, year, m, true);
                Map<Integer, BigDecimal> schedule = schedule(rules, p.joiningDate(), p.lastWorkingDay(), year);
                Set<Integer> due = dueMonths(schedule, rules, year, today);
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
