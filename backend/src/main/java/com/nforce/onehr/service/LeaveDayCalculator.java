package com.nforce.onehr.service;

import com.nforce.onehr.entity.LeaveDurationType;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.repository.LeaveRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * LV-07 (ONEHR-893): how many days a leave request takes. A range may cross weekly offs and holidays; only
 * working days count, unless the type's sandwich rule in the employee's plan also counts the non-working days
 * between two leave days (holidays, weekly offs or both; optionally only when the leave covers at least N working
 * days; optionally also between this request and an adjacent request of the same type). Per-occurrence leave
 * (Maternity) counts calendar days. A range with no working day at all is refused.
 */
@Service
@RequiredArgsConstructor
public class LeaveDayCalculator {

    public static final String WORKING = "WORKING";
    public static final String SANDWICHED = "SANDWICHED";
    public static final String WEEKLY_OFF = "WEEKLY_OFF";
    public static final String HOLIDAY = "HOLIDAY";

    private static final BigDecimal ONE = BigDecimal.ONE;
    // Same format as the pre-LV-07 rejection ("Saturday, 5 Dec 2026") — the message for a single non-working date is unchanged.
    private static final DateTimeFormatter LEAVE_DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMM yyyy", Locale.ENGLISH);
    /** How far either side of a request an adjacent request is looked for (the gap is only non-working days). */
    private static final int ADJACENT_LOOKAROUND_DAYS = 31;

    private final LeaveCalendar calendar;
    private final LeavePlanRuleService planRuleService;
    private final LeaveRequestRepository requestRepository;

    /** One date of the range (or of a gap to an adjacent request): counted (WORKING, SANDWICHED) or not. */
    public record Day(LocalDate date, String kind, BigDecimal days, String note) {}

    /** {@code days}: every date looked at, counted or not, in date order. */
    public record Result(BigDecimal total, BigDecimal workingDays, BigDecimal sandwichedDays, List<Day> days, LeaveDayCounts counts) {

        /** "4 days: 2 working + 2 sandwiched", or "3 days". */
        public String summary() {
            String head = fmt(total) + (total.compareTo(ONE) == 0 ? " day" : " days");
            return sandwichedDays.signum() == 0 ? head
                    : head + ": " + fmt(workingDays) + " working + " + fmt(sandwichedDays) + " sandwiched";
        }
    }

    /**
     * The day count of a request. {@code fraction}: the days of a half-day (0.5) or hourly request on its single
     * date; null for full days. {@code excludeRequestId}/{@code createdAt}: the request itself when editing (its
     * creation time decides which of two adjacent requests carries the gap between them); null for a new one.
     * Not transactional on purpose: a refused range throws, and callers (preview, reject's recalculation) must be
     * able to catch that without their own transaction being marked rollback-only.
     */
    public Result calculate(UUID employeeUserId, LeaveType type, LocalDate start, LocalDate end, BigDecimal fraction,
                            UUID excludeRequestId, LocalDateTime createdAt) {
        LeavePlanCatalog.Sandwich sandwich = planRuleService.ruleFor(employeeUserId, type.getId(), start)
                .map(r -> r.limits().sandwich()).orElse(LeavePlanCatalog.Sandwich.OFF);
        LeaveCalendar.Days days = type.isPerOccurrence() ? null : calendar.forEmployee(employeeUserId);
        List<LeaveRequest> neighbours = List.of();
        if (sandwich.enabled() && sandwich.acrossRequests() && fraction == null && !type.isPerOccurrence()) {
            neighbours = requestRepository.findActiveInRange(employeeUserId, LeaveRuleValidator.ACTIVE,
                            start.minusDays(ADJACENT_LOOKAROUND_DAYS), end.plusDays(ADJACENT_LOOKAROUND_DAYS)).stream()
                    .filter(r -> r.getLeaveType().getId().equals(type.getId()))
                    .filter(r -> !r.getId().equals(excludeRequestId))
                    .toList();
        }
        return count(start, end, fraction, type.isPerOccurrence(), sandwich, days, neighbours, createdAt);
    }

    /** The counting itself, free of lookups (unit-tested directly). */
    static Result count(LocalDate start, LocalDate end, BigDecimal fraction, boolean perOccurrence,
                        LeavePlanCatalog.Sandwich sandwich, LeaveCalendar.Days days, List<LeaveRequest> neighbours,
                        LocalDateTime createdAt) {
        NavigableMap<LocalDate, BigDecimal> working = new TreeMap<>();
        NavigableMap<LocalDate, BigDecimal> sandwiched = new TreeMap<>();
        List<Day> out = new ArrayList<>();

        if (perOccurrence) { // Maternity-style leave: calendar days, weekly offs and holidays included
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                working.put(d, ONE);
                out.add(new Day(d, WORKING, ONE, null));
            }
            return result(working, sandwiched, out);
        }
        if (fraction != null) { // half day / hourly: one date, which must be a working day
            requireWorking(start, days);
            working.put(start, fraction);
            out.add(new Day(start, WORKING, fraction, null));
            return result(working, sandwiched, out);
        }

        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (!days.nonWorking(d)) working.put(d, ONE);
        }
        if (working.isEmpty()) {
            if (start.equals(end)) requireWorking(start, days);
            throw new IllegalArgumentException("Leave from " + start.format(LEAVE_DATE_FMT) + " to " + end.format(LEAVE_DATE_FMT)
                    + " covers only weekly offs and holidays. Please include at least one working day.");
        }
        LocalDate first = working.firstKey();
        LocalDate last = working.lastKey();
        BigDecimal workingCount = BigDecimal.valueOf(working.size());

        // Inside the range: non-working days between the first and last working day may be sandwiched.
        boolean inRange = sandwich.enabled() && meetsMinimum(sandwich, workingCount);
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (working.containsKey(d)) continue;
            boolean between = d.isAfter(first) && d.isBefore(last);
            if (between && inRange && sandwichable(d, sandwich, days)) sandwiched.put(d, ONE);
        }

        // Across requests: the non-working days between this request and an older adjacent one of the same type.
        if (sandwich.enabled() && sandwich.acrossRequests()) {
            LocalDate before = days.previousWorkingDay(first);
            adjacent(neighbours, before, createdAt).ifPresent(n -> {
                if (meetsMinimum(sandwich, workingCount.add(n.getTotalDays()))) {
                    for (LocalDate d = before.plusDays(1); d.isBefore(first); d = d.plusDays(1)) {
                        if (sandwichable(d, sandwich, days)) sandwiched.put(d, ONE);
                    }
                }
            });
            LocalDate after = days.nextWorkingDay(last);
            adjacent(neighbours, after, createdAt).ifPresent(n -> {
                if (meetsMinimum(sandwich, workingCount.add(n.getTotalDays()))) {
                    for (LocalDate d = last.plusDays(1); d.isBefore(after); d = d.plusDays(1)) {
                        if (sandwichable(d, sandwich, days)) sandwiched.put(d, ONE);
                    }
                }
            });
        }

        // Every date looked at: the range plus any sandwiched gap outside it.
        LocalDate from = sandwiched.isEmpty() || !sandwiched.firstKey().isBefore(start) ? start : sandwiched.firstKey();
        LocalDate to = sandwiched.isEmpty() || !sandwiched.lastKey().isAfter(end) ? end : sandwiched.lastKey();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (working.containsKey(d)) {
                out.add(new Day(d, WORKING, ONE, null));
            } else if (sandwiched.containsKey(d)) {
                out.add(new Day(d, SANDWICHED, ONE, describe(d, days)));
            } else if (!d.isBefore(start) && !d.isAfter(end)) {
                out.add(new Day(d, days.holiday(d) != null ? HOLIDAY : WEEKLY_OFF, BigDecimal.ZERO, describe(d, days)));
            }
        }
        return result(working, sandwiched, out);
    }

    /** An active same-type request covering {@code date} made before this one (new requests are made now). */
    private static Optional<LeaveRequest> adjacent(List<LeaveRequest> neighbours, LocalDate date, LocalDateTime createdAt) {
        if (date == null) return Optional.empty();
        return neighbours.stream()
                .filter(r -> !date.isBefore(r.getStartDate()) && !date.isAfter(r.getEndDate()))
                .filter(r -> !r.isHalfDay() && !LeaveDurationType.HOURLY.equals(r.getDurationType()))
                .filter(r -> createdAt == null || r.getCreatedAt() == null || r.getCreatedAt().isBefore(createdAt))
                .min(Comparator.comparing(LeaveRequest::getStartDate));
    }

    private static boolean meetsMinimum(LeavePlanCatalog.Sandwich s, BigDecimal workingDays) {
        return s.minDays() == null || workingDays.compareTo(s.minDays()) >= 0;
    }

    private static boolean sandwichable(LocalDate d, LeavePlanCatalog.Sandwich s, LeaveCalendar.Days days) {
        return (days.holiday(d) != null && s.countsHolidays()) || (days.weeklyOff(d) && s.countsWeekOffs());
    }

    private static String describe(LocalDate d, LeaveCalendar.Days days) {
        String holiday = days.holiday(d);
        return holiday != null ? holiday : "Weekly off";
    }

    /** The pre-LV-07 messages, still used for a single date that isn't a working day. */
    private static void requireWorking(LocalDate date, LeaveCalendar.Days days) {
        String holiday = days.holiday(date);
        if (holiday != null) {
            throw new IllegalArgumentException("Leave cannot be applied for " + date.format(LEAVE_DATE_FMT)
                    + " — it is a holiday (" + holiday + "). Please choose only your working days.");
        }
        if (days.weeklyOff(date)) {
            throw new IllegalArgumentException("Leave cannot be applied for " + date.format(LEAVE_DATE_FMT)
                    + " — it is a weekly-off day under your Weekly Off Policy. Please choose only your working days.");
        }
    }

    private static Result result(NavigableMap<LocalDate, BigDecimal> working, NavigableMap<LocalDate, BigDecimal> sandwiched,
                                 List<Day> days) {
        LeaveDayCounts counts = new LeaveDayCounts(working, sandwiched);
        return new Result(counts.total(), counts.workingTotal(), counts.sandwichedTotal(), List.copyOf(days), counts);
    }

    private static String fmt(BigDecimal d) {
        return d.stripTrailingZeros().toPlainString();
    }
}
