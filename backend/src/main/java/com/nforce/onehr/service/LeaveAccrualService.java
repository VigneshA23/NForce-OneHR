package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.LeaveConfigDtos.AccrualCreditResponse;
import com.nforce.onehr.dto.LeaveConfigDtos.AccrualPreview;
import com.nforce.onehr.dto.LeaveConfigDtos.AccrualPreviewLine;
import com.nforce.onehr.dto.LeaveConfigDtos.AccrualRunResult;
import com.nforce.onehr.dto.LeaveEmployeeProfile;
import com.nforce.onehr.entity.LeaveAccrualCredit;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveEntitlementPolicy;
import com.nforce.onehr.entity.LeavePlan;
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
 *   <li><b>Eligible months</b>: from the joining month to December, stopping after the month of the last
 *       working day. A month whose plan doesn't include the type (or whose "applies to" rules exclude the
 *       employee) is not credited.</li>
 *   <li><b>Joining and exit months</b> (LV-04, per plan): by MONTHS they count whole — or not at all when the
 *       plan's cut-off day says so (joined after it / left before it); by CALENDAR_DAYS they count the share of
 *       the month served.</li>
 *   <li><b>MONTHLY</b>: a month is credited once its 1st has arrived; a partly-served month is rounded per the
 *       plan (nearest / down / up to 0.5, or not at all).</li>
 *   <li><b>YEARLY</b> (all at once): a run of months under one plan and quota is credited once its first
 *       month has arrived. A part-year run (joiner, leaver, plan switch) is rounded per the plan (by default
 *       to the nearest 0.5), the difference on its last month.</li>
 * </ul>
 * LV-03 adds QUARTERLY and HALF_YEARLY crediting (a quarter's / half-year's months credited together), credit
 * timing (START: on the period's first day; END: once the period is over) and pausing: a period's credit is
 * withheld, as a 0-day PAUSED ledger row, when loss of pay or unpaid leave in the period it depends on is over
 * the plan's limit. {@link #preview} shows what a run would post without posting it.
 * Worked example: quota 15, joined 1 October, whole months → 3 × 1.25 = 3.75, nearest 0.5 → 4.
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
    private final LeaveAccrualPauseFacts pauseFacts;

    // ── The schedule (pure) ─────────────────────────────────────────────────────────────────────

    public static BigDecimal monthShare(BigDecimal quota, int month) {
        return cumulative(quota, month).subtract(cumulative(quota, month - 1));
    }

    private static BigDecimal cumulative(BigDecimal quota, int months) {
        return quota.multiply(BigDecimal.valueOf(months)).divide(TWELVE, 2, RoundingMode.HALF_UP);
    }

    /**
     * LV-04: how a plan prorates a part-year entitlement. {@code method} MONTHS or CALENDAR_DAYS; {@code cutoffDay}
     * (MONTHS) joining on/before it counts the joining month, leaving on/after it the exit month (null = always);
     * {@code rounding} NEAREST_HALF, DOWN_HALF, UP_HALF or NONE.
     */
    public record Proration(String method, Integer cutoffDay, String rounding) {
        /** How crediting worked before LV-04: whole joining and exit months, nearest 0.5. */
        public static final Proration DEFAULT = new Proration(LeavePlan.PRORATE_MONTHS, null, LeavePlan.ROUND_NEAREST_HALF);

        public static Proration of(LeavePlan p) {
            return new Proration(p.getProrationMethod() == null ? LeavePlan.PRORATE_MONTHS : p.getProrationMethod(),
                    p.getProrationCutoffDay(), p.getProrationRounding() == null ? LeavePlan.ROUND_NEAREST_HALF : p.getProrationRounding());
        }

        /**
         * The part of month {@code m} of {@code year} the employee counts for (0 to 1): 1 unless it is the joining
         * or the exit month.
         */
        BigDecimal fraction(int year, int m, LocalDate joining, LocalDate lastWorkingDay) {
            boolean joinMonth = joining != null && joining.getYear() == year && joining.getMonthValue() == m;
            boolean exitMonth = lastWorkingDay != null && lastWorkingDay.getYear() == year && lastWorkingDay.getMonthValue() == m;
            if (!joinMonth && !exitMonth) return BigDecimal.ONE;
            int length = java.time.YearMonth.of(year, m).lengthOfMonth();
            int from = joinMonth ? joining.getDayOfMonth() : 1;
            int to = exitMonth ? lastWorkingDay.getDayOfMonth() : length;
            if (to < from) return ZERO;
            if (LeavePlan.PRORATE_CALENDAR_DAYS.equals(method)) {
                return BigDecimal.valueOf(to - from + 1).divide(BigDecimal.valueOf(length), 6, RoundingMode.HALF_UP);
            }
            if (cutoffDay == null) return BigDecimal.ONE;
            boolean counts = (!joinMonth || from <= cutoffDay) && (!exitMonth || to >= Math.min(cutoffDay, length));
            return counts ? BigDecimal.ONE : ZERO;
        }

        /** {@code days} rounded to a half day per {@link #rounding} (2 decimals for NONE). */
        BigDecimal round(BigDecimal days) {
            RoundingMode mode = switch (rounding == null ? LeavePlan.ROUND_NEAREST_HALF : rounding) {
                case LeavePlan.ROUND_DOWN_HALF -> RoundingMode.FLOOR;
                case LeavePlan.ROUND_UP_HALF -> RoundingMode.CEILING;
                case LeavePlan.ROUND_NONE -> null;
                default -> RoundingMode.HALF_UP;
            };
            if (mode == null) return days.setScale(2, RoundingMode.HALF_UP);
            return days.multiply(TWO).setScale(0, mode).divide(TWO, 2, RoundingMode.UNNECESSARY);
        }
    }

    /**
     * What governs one month's credit: the plan (null in single-rule calls), the yearly quota, the frequency, the
     * plan's proration and (LV-03) when the period is credited and whether it can be paused.
     */
    public record MonthRule(UUID planId, BigDecimal quota, String frequency, Proration proration, LeavePlanCatalog.Accrual accrual) {

        public MonthRule(UUID planId, BigDecimal quota, String frequency) {
            this(planId, quota, frequency, Proration.DEFAULT, LeavePlanCatalog.Accrual.DEFAULT);
        }

        public MonthRule(UUID planId, BigDecimal quota, String frequency, Proration proration) {
            this(planId, quota, frequency, proration, LeavePlanCatalog.Accrual.DEFAULT);
        }

        String freq() {
            return frequency == null ? LeaveEntitlementPolicy.YEARLY : frequency;
        }

        /** Months in one credit period: 1 (MONTHLY), 3 (QUARTERLY), 6 (HALF_YEARLY) or 12 (YEARLY). */
        int periodMonths() {
            return switch (freq()) {
                case LeaveEntitlementPolicy.MONTHLY -> 1;
                case LeaveEntitlementPolicy.QUARTERLY -> 3;
                case LeaveEntitlementPolicy.HALF_YEARLY -> 6;
                default -> 12;
            };
        }

        /** The first month of the credit period that month {@code m} belongs to. */
        int periodStart(int m) {
            int n = periodMonths();
            return (m - 1) / n * n + 1;
        }

        boolean atEnd() {
            return accrual != null && accrual.atEnd();
        }

        boolean sameRun(MonthRule o) {
            return o != null && Objects.equals(planId, o.planId) && freq().equals(o.freq())
                    && quota.compareTo(o.quota) == 0 && atEnd() == o.atEnd();
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
     * not credited. The joining and exit months are prorated by their plan's {@link Proration} (a month that
     * doesn't count at all is left out). Months credited together (see {@link #periodGroups}) that don't make up a
     * whole period (a joiner, a leaver, a plan switch, a partly-served month) are rounded per the plan, the
     * difference on the last month.
     */
    public static Map<Integer, BigDecimal> schedule(IntFunction<MonthRule> ruleOfMonth, LocalDate joining,
                                                    LocalDate lastWorkingDay, int year) {
        TreeMap<Integer, BigDecimal> out = new TreeMap<>();
        int first = joining == null || joining.getYear() < year ? 1 : joining.getYear() == year ? joining.getMonthValue() : 13;
        int last = lastWorkingDay == null || lastWorkingDay.getYear() > year ? 12
                : lastWorkingDay.getYear() == year ? lastWorkingDay.getMonthValue() : 0;
        Map<Integer, MonthRule> rules = new HashMap<>();
        Set<Integer> partial = new HashSet<>();
        for (int m = first; m <= last; m++) {
            MonthRule r = ruleOfMonth.apply(m);
            if (r == null || r.quota() == null || r.quota().signum() <= 0) continue;
            Proration p = r.proration() == null ? Proration.DEFAULT : r.proration();
            BigDecimal fraction = p.fraction(year, m, joining, lastWorkingDay);
            if (fraction.signum() == 0) continue;
            BigDecimal share = monthShare(r.quota(), m);
            if (fraction.compareTo(BigDecimal.ONE) < 0) {
                share = share.multiply(fraction).setScale(2, RoundingMode.HALF_UP);
                partial.add(m);
            }
            rules.put(m, r);
            out.put(m, share);
        }
        for (List<Integer> group : periodGroups(out.keySet(), rules)) {
            MonthRule r = rules.get(group.get(0));
            Proration p = r.proration() == null ? Proration.DEFAULT : r.proration();
            int gFirst = group.get(0), gLast = group.get(group.size() - 1);
            boolean wholePeriod = gFirst == r.periodStart(gFirst) && gLast == r.periodStart(gFirst) + r.periodMonths() - 1
                    && group.stream().noneMatch(partial::contains);
            if (wholePeriod) continue;
            BigDecimal total = group.stream().map(out::get).reduce(ZERO, BigDecimal::add);
            // The difference goes on the group's last month; a rounding-down larger than that month (a short
            // exit month) continues on the months before it, so no month goes negative.
            BigDecimal diff = p.round(total).subtract(total);
            for (int i = group.size() - 1; i >= 0 && diff.signum() != 0; i--) {
                int month = group.get(i);
                BigDecimal adjusted = out.get(month).add(diff);
                if (adjusted.signum() >= 0 || i == 0) {
                    out.put(month, adjusted.max(ZERO));
                    diff = ZERO;
                } else {
                    diff = adjusted;
                    out.put(month, ZERO);
                }
            }
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

    /**
     * LV-03: months credited together, meaning consecutive months of one run (same plan, quota, frequency and
     * timing) in the same credit period: a single month (MONTHLY), a quarter, a half-year or the year (YEARLY).
     */
    static List<List<Integer>> periodGroups(Collection<Integer> months, Map<Integer, MonthRule> rules) {
        List<List<Integer>> out = new ArrayList<>();
        for (List<Integer> run : runs(months, rules)) {
            List<Integer> current = null;
            int start = -1;
            for (int m : run) {
                int periodStart = rules.get(m).periodStart(m);
                if (current == null || periodStart != start) {
                    current = new ArrayList<>();
                    out.add(current);
                    start = periodStart;
                }
                current.add(m);
            }
        }
        return out;
    }

    /**
     * LV-03: one credit: the months credited together, under {@code rule}, on {@code creditDate}. A pause looks at
     * [{@code pauseFrom}, {@code pauseTo}]: the period itself when credited at the END, the period before it when
     * credited at the START (its own period hasn't happened yet).
     */
    public record Credit(List<Integer> months, MonthRule rule, LocalDate creditDate, LocalDate pauseFrom, LocalDate pauseTo) {}

    /**
     * The credits of {@code schedule} in date order. START: on the 1st of the first month (a joiner's first months
     * at once). END: the day after the period's last day, or after the last working day for a leaver's last period.
     */
    public static List<Credit> credits(Map<Integer, BigDecimal> schedule, IntFunction<MonthRule> ruleOfMonth, int year,
                                       LocalDate lastWorkingDay) {
        Map<Integer, MonthRule> rules = new HashMap<>();
        for (int m : schedule.keySet()) rules.put(m, ruleOfMonth.apply(m));
        List<Credit> out = new ArrayList<>();
        for (List<Integer> group : periodGroups(schedule.keySet(), rules)) {
            int first = group.get(0), last = group.get(group.size() - 1);
            MonthRule r = rules.get(first);
            LocalDate periodStart = LocalDate.of(year, r.periodStart(first), 1);
            if (r.atEnd()) {
                LocalDate end = java.time.YearMonth.of(year, last).atEndOfMonth();
                if (lastWorkingDay != null && lastWorkingDay.isBefore(end)) end = lastWorkingDay;
                out.add(new Credit(List.copyOf(group), r, end.plusDays(1), LocalDate.of(year, first, 1), end));
            } else {
                out.add(new Credit(List.copyOf(group), r, LocalDate.of(year, first, 1),
                        periodStart.minusMonths(r.periodMonths()), periodStart.minusDays(1)));
            }
        }
        return out;
    }

    /** The months of {@code schedule} whose credit date has arrived by {@code today} (one quota and frequency). */
    public static Set<Integer> dueMonths(Map<Integer, BigDecimal> schedule, String frequency, int year, LocalDate today) {
        MonthRule r = new MonthRule(null, BigDecimal.ONE, frequency);
        return dueMonths(schedule, m -> r, year, today);
    }

    public static Set<Integer> dueMonths(Map<Integer, BigDecimal> schedule, IntFunction<MonthRule> ruleOfMonth, int year,
                                         LocalDate today) {
        return dueMonths(schedule, ruleOfMonth, year, today, null);
    }

    /** The months whose credit (see {@link #credits}) is dated on or before {@code today}. */
    public static Set<Integer> dueMonths(Map<Integer, BigDecimal> schedule, IntFunction<MonthRule> ruleOfMonth, int year,
                                         LocalDate today, LocalDate lastWorkingDay) {
        Set<Integer> due = new HashSet<>();
        for (Credit c : credits(schedule, ruleOfMonth, year, lastWorkingDay)) {
            if (!c.creditDate().isAfter(today)) due.addAll(c.months());
        }
        return due;
    }

    /**
     * LV-03: why {@code c} is withheld, or null when it isn't: more loss of pay, or more approved unpaid leave, in
     * the period it depends on than its rule allows.
     */
    static String pauseReason(Credit c, LeaveAccrualPauseFacts.Facts facts) {
        LeavePlanCatalog.Accrual a = c.rule().accrual();
        if (a == null || !a.pauses()) return null;
        String period = c.pauseFrom() + " to " + c.pauseTo();
        if (a.pauseLopOverDays() != null) {
            BigDecimal lop = facts.lopDays(c.pauseFrom(), c.pauseTo());
            if (lop.compareTo(a.pauseLopOverDays()) > 0) {
                return "Loss of pay " + plain(lop) + " day(s) from " + period + " (more than " + plain(a.pauseLopOverDays()) + ")";
            }
        }
        if (a.pauseUnpaidOverDays() != null) {
            BigDecimal unpaid = facts.unpaidDays(c.pauseFrom(), c.pauseTo());
            if (unpaid.compareTo(a.pauseUnpaidOverDays()) > 0) {
                return "Unpaid leave " + plain(unpaid) + " day(s) from " + period + " (more than " + plain(a.pauseUnpaidOverDays()) + ")";
            }
        }
        return null;
    }

    private static String plain(BigDecimal d) {
        return d.stripTrailingZeros().toPlainString();
    }

    /** LV-03: a credit that is due and not yet posted: its open months, their days, and why it is paused (null = credit it). */
    public record Due(Credit credit, List<Integer> months, BigDecimal days, String pauseReason) {
        public boolean paused() {
            return pauseReason != null;
        }
    }

    /**
     * The credits dated on or before {@code asOf} with months not yet in the ledger ({@code posted}), each either to
     * credit or, when its rule's pause applies, to withhold. Months of 0 days are left out.
     */
    static List<Due> dues(Map<Integer, BigDecimal> schedule, IntFunction<MonthRule> ruleOfMonth, int year, LocalDate lastWorkingDay,
                          Set<Integer> posted, LocalDate asOf, LeaveAccrualPauseFacts.Facts facts) {
        List<Due> out = new ArrayList<>();
        for (Credit c : credits(schedule, ruleOfMonth, year, lastWorkingDay)) {
            if (c.creditDate().isAfter(asOf)) continue;
            List<Integer> open = c.months().stream()
                    .filter(m -> !posted.contains(m) && schedule.get(m) != null && schedule.get(m).signum() != 0).toList();
            if (open.isEmpty()) continue;
            BigDecimal days = open.stream().map(schedule::get).reduce(ZERO, BigDecimal::add);
            out.add(new Due(c, open, days, pauseReason(c, facts)));
        }
        return out;
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
        final LocalDate on = date;
        Optional<LeavePlanCatalog.TypeRule> rule = requireEligible
                ? LeaveEligibilityService.applicableRule(catalog, employeeUserId, profile, type.getId(), date)
                        // LV-06: a month counts only once the type's minimum tenure is reached on its date
                        .filter(r -> r.eligibility().tenureMet(employeeUserId, profile, on))
                : catalog.ruleFor(employeeUserId, type.getId(), date);
        return rule.map(r -> new MonthRule(r.planId(), r.creditedQuota(), r.creditFrequency(),
                catalog.plan(r.planId()).map(Proration::of).orElse(Proration.DEFAULT),
                r.accrual() == null ? LeavePlanCatalog.Accrual.DEFAULT : r.accrual())).orElse(null);
    }

    /**
     * LV-06: the type isn't allowed during probation and the employee is on probation {@code today}: nothing is
     * credited yet. Once probation is over the due months — including those of the probation period — are
     * credited by the next run, so the entitlement is caught up on confirmation.
     */
    static boolean heldForProbation(LeavePlanCatalog catalog, UUID employeeUserId, LeaveEmployeeProfile profile,
                                    LeaveType type, LocalDate today) {
        return catalog.ruleFor(employeeUserId, type.getId(), today)
                .map(r -> r.eligibility().probationBlocks(employeeUserId, profile, today)).orElse(false);
    }

    // ── Per balance ─────────────────────────────────────────────────────────────────────────────

    public LocalDate today() {
        return LocalDate.now(ZoneId.of(attendanceProperties.getZone()));
    }

    /**
     * Credits every due, not-yet-credited month of (employee, type, year) and returns the days added; a due credit
     * whose pause applies (LV-03) is recorded as PAUSED instead. Creates the balance row (0 credited) if needed.
     * Nothing for an employee no longer employed, for inactive types, or for anything but regular paid leave.
     */
    @Transactional
    public BigDecimal accrue(UUID employeeUserId, LeaveType type, int year) {
        if (!type.isAnnualBalance() || !type.isActive()) return ZERO;
        LeaveEmployeeProfile profile = employeeRepository.findLeaveProfile(employeeUserId).orElse(null);
        if (profile == null || !profile.employed()) return ZERO;
        LeavePlanCatalog catalog = planRuleService.catalog(List.of(employeeUserId));
        Posting posting = plan(catalog, profile, type, year, postedMonths(employeeUserId, type, year), today());
        return posting == null ? ZERO : post(posting)[0];
    }

    private Set<Integer> postedMonths(UUID employeeUserId, LeaveType type, int year) {
        Set<Integer> out = new HashSet<>();
        creditRepository.findByEmployeeUserIdAndLeaveTypeIdAndYearOrderByMonthAsc(employeeUserId, type.getId(), year)
                .forEach(c -> out.add(c.getMonth()));
        return out;
    }

    /** LV-03: what a run would post for one (employee, type, year): the due credits, each credited or paused. */
    public record Posting(UUID employeeUserId, LeaveType type, int year, Map<Integer, BigDecimal> schedule, List<Due> dues) {}

    /**
     * What is due for (employee, type, year) by {@code asOf} and not yet {@code posted}; null when nothing is (or the
     * type is held while the employee is on probation, LV-06).
     */
    private Posting plan(LeavePlanCatalog catalog, LeaveEmployeeProfile p, LeaveType type, int year, Set<Integer> posted, LocalDate asOf) {
        if (heldForProbation(catalog, p.userId(), p, type, asOf)) return null;
        IntFunction<MonthRule> rules = m -> monthRule(catalog, p.userId(), p, type, year, m, true);
        Map<Integer, BigDecimal> schedule = schedule(rules, p.joiningDate(), p.lastWorkingDay(), year);
        if (schedule.isEmpty()) return null;
        List<Due> dues = dues(schedule, rules, year, p.lastWorkingDay(), posted, asOf, pauseFacts.of(p.userId()));
        return dues.isEmpty() ? null : new Posting(p.userId(), type, year, schedule, dues);
    }

    /** Posts {@code posting}: {days credited, months paused}. Only rows this call inserts count (re-runs add nothing). */
    private BigDecimal[] post(Posting posting) {
        Set<Integer> credit = new HashSet<>(), pause = new HashSet<>();
        for (Due d : posting.dues()) (d.paused() ? pause : credit).addAll(d.months());
        BigDecimal added = credit(posting.employeeUserId(), posting.type(), posting.year(), posting.schedule(), credit);
        int paused = 0;
        for (int m : sortedMonths(pause)) {
            paused += creditRepository.insertPausedIfAbsent(UUID.randomUUID(), posting.employeeUserId(), posting.type().getId(),
                    posting.year(), m);
        }
        return new BigDecimal[] {added, BigDecimal.valueOf(paused)};
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
        Map<Integer, BigDecimal> schedule = schedule(rules, profile.joiningDate(), profile.lastWorkingDay(), year);
        Set<Integer> posted = postedMonths(employeeUserId, type, year);
        BigDecimal upcoming = ZERO;
        Credit next = null;
        BigDecimal nextDays = ZERO;
        for (Credit c : credits(schedule, rules, year, profile.lastWorkingDay())) {
            BigDecimal open = c.months().stream().filter(m -> !posted.contains(m)).map(schedule::get).reduce(ZERO, BigDecimal::add);
            boolean anyOpen = c.months().stream().anyMatch(m -> !posted.contains(m));
            if (!anyOpen) continue;
            upcoming = upcoming.add(open);
            if (next == null) {
                next = c;
                nextDays = open;
            }
        }
        if (next == null) return new Projection(frequency, ZERO, null, null);
        // the credit arrives on its date: the 1st of its first month (START) or once its period is over (END)
        return new Projection(next.rule().freq(), upcoming, next.creditDate(), nextDays);
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
                    // LV-03: a withheld month stays withheld
                    if (LeaveAccrualCredit.SOURCE_PAUSED.equals(r.getSource())) continue;
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
                    result.creditsAdded() + previous.creditsAdded(), result.failures() + previous.failures(), List.of(),
                    result.creditsPaused() + previous.creditsPaused());
        }
        // YE-07: every run leaves one audit entry (its "last run" status) and alerts Super Admins on failures.
        List<String> failedCodes = jobMonitor.recordRun(LeaveJobMonitor.ACCRUAL, "Leave crediting", actorId, Map.of(
                "year", year, "employeesChecked", result.employeesChecked(),
                "creditsAdded", result.creditsAdded(), "creditsPaused", result.creditsPaused(), "failures", result.failures()), failed);
        return new AccrualRunResult(result.year(), result.employeesChecked(), result.creditsAdded(), result.failures(), failedCodes,
                result.creditsPaused());
    }

    /** Every (employee, type) of {@code year} with something due by {@code asOf}; nothing is written. */
    private List<Posting> plans(int year, LocalDate asOf) {
        List<LeaveEmployeeProfile> employees = employeeRepository.findLeaveProfiles().stream()
                .filter(LeaveEmployeeProfile::employed).toList();
        LeavePlanCatalog catalog = planRuleService.catalogForAll();
        List<Posting> out = new ArrayList<>();
        for (LeaveType type : leaveTypeRepository.findByActiveTrueOrderByNameAsc()) {
            if (!type.isAnnualBalance()) continue;
            Map<UUID, Set<Integer>> posted = new HashMap<>();
            for (Object[] row : creditRepository.findCreditedMonths(type.getId(), year)) {
                posted.computeIfAbsent((UUID) row[0], k -> new HashSet<>()).add((Integer) row[1]);
            }
            for (LeaveEmployeeProfile p : employees) {
                Posting posting = plan(catalog, p, type, year, posted.getOrDefault(p.userId(), Set.of()), asOf);
                if (posting != null) out.add(posting);
            }
        }
        return out;
    }

    private AccrualRunResult runYear(int year, LocalDate today, Set<UUID> failed) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        int checked = 0, added = 0, paused = 0, failures = 0;
        for (Posting posting : plans(year, today)) {
            checked++;
            try {
                BigDecimal[] r = tx.execute(s -> post(posting));
                if (r != null && r[0].signum() != 0) added++;
                if (r != null) paused += r[1].intValue();
            } catch (Exception e) {
                failures++;
                failed.add(posting.employeeUserId());
                log.warn("Leave crediting failed for employee {} type {} year {}: {}", posting.employeeUserId(),
                        posting.type().getCode(), year, e.getMessage());
            }
        }
        return new AccrualRunResult(year, checked, added, failures, List.of(), paused);
    }

    /**
     * LV-03 dry run: what a crediting run on {@code asOf} (default today) would post, without writing anything. One line
     * per credit: credited, or paused with the reason. In January it includes what is still due for the previous year.
     */
    @Transactional(readOnly = true)
    public AccrualPreview preview(LocalDate asOf) {
        LocalDate on = asOf != null ? asOf : today();
        int year = LeaveBalanceService.leaveYearOf(on);
        List<Posting> postings = new ArrayList<>(plans(year, on));
        if (on.getMonthValue() == 1) postings.addAll(plans(year - 1, on));
        Set<UUID> ids = new HashSet<>();
        postings.forEach(p -> ids.add(p.employeeUserId()));
        Map<UUID, String> names = new HashMap<>(), codes = new HashMap<>();
        if (!ids.isEmpty()) {
            employeeRepository.findNamesByUserIds(ids).forEach(r -> names.put((UUID) r[0], (String) r[1]));
            employeeRepository.findCodesByUserIds(ids).forEach(r -> codes.put((UUID) r[0], (String) r[1]));
        }
        List<AccrualPreviewLine> lines = new ArrayList<>();
        BigDecimal credited = ZERO;
        int pausedCount = 0;
        for (Posting p : postings) {
            for (Due d : p.dues()) {
                lines.add(new AccrualPreviewLine(p.employeeUserId(), names.get(p.employeeUserId()), codes.get(p.employeeUserId()),
                        p.type().getId(), p.type().getName(), p.year(), d.months(), d.days(), d.credit().creditDate(),
                        d.credit().rule().freq(), d.paused() ? "PAUSED" : "CREDIT", d.pauseReason()));
                if (d.paused()) pausedCount++;
                else credited = credited.add(d.days());
            }
        }
        lines.sort(java.util.Comparator.comparing((AccrualPreviewLine l) -> l.employeeName() == null ? "" : l.employeeName())
                .thenComparing(AccrualPreviewLine::leaveTypeName).thenComparing(AccrualPreviewLine::year)
                .thenComparing(l -> l.months().get(0)));
        return new AccrualPreview(on, ids.size(), credited, pausedCount, lines);
    }

    static List<Integer> sortedMonths(Set<Integer> months) {
        List<Integer> out = new ArrayList<>(months);
        out.sort(Integer::compare);
        return out;
    }
}
