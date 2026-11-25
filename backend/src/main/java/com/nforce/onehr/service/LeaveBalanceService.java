package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.LeaveBalanceResponse;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveEntitlementPolicy;
import com.nforce.onehr.entity.LeaveOccurrence;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveEntitlementPolicyRepository;
import com.nforce.onehr.repository.LeaveOccurrenceRepository;
import com.nforce.onehr.repository.LeaveRequestRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.math.BigDecimal.ZERO;

/**
 * The single source of truth for leave balances (V310). Every paid leave type keeps its OWN balance
 * — the old Annual/Sick/Casual consolidation is gone — and every reader/writer of a balance
 * (LeaveService, attendance penalties, payroll, payslips, the admin screens) goes through here.
 *
 * <ul>
 *   <li><b>ANNUAL</b> paid types: one {@link LeaveBalance} per employee, type and leave year, with a
 *       current-year bucket (entitlement from {@link LeaveEntitlementPolicy}) and a carried-forward
 *       bucket that can only cover leave dated on/before its expiry date. Rows are created lazily.</li>
 *   <li><b>PER_OCCURRENCE</b> paid types (Maternity): a {@link LeaveOccurrence}, not a yearly balance.</li>
 *   <li><b>UNPAID</b> types: unlimited — nothing is ever created, read or deducted.</li>
 * </ul>
 * Pending requests reserve days in each leave-year bucket they touch; approval deducts.
 *
 * <p>V314: {@code total_days} is what has been <b>credited</b> so far ({@link LeaveAccrualService}).
 * Requests and approvals may also use the credits still to come this year (leave in advance), so
 * {@code used_days} can exceed {@code total_days}; later credits recover it. What is shown as the
 * balance, the "use paid leave before unpaid" rule and penalty debits use credited days only.
 * Types an employee isn't eligible for ({@link LeaveEligibilityService}) get no new balance rows.
 */
@Service
@RequiredArgsConstructor
public class LeaveBalanceService {

    private static final LocalDate FAR_PAST = LocalDate.of(1900, 1, 1);
    private static final LocalDate FAR_FUTURE = LocalDate.of(9999, 12, 31);

    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveEntitlementPolicyRepository policyRepository;
    private final LeaveOccurrenceRepository occurrenceRepository;
    private final AttendanceProperties attendanceProperties;
    private final LeaveAccrualService accrualService;
    private final LeaveEligibilityService eligibilityService;

    // ── Leave year ──────────────────────────────────────────────────────────────────────────────
    // January–December: there is no organisation-level leave-year setting. Every leave-year
    // calculation in the codebase goes through these, so changing the leave year is one place.

    public static int leaveYearOf(LocalDate date) {
        return date.getYear();
    }

    public static LocalDate yearStart(int year) {
        return LocalDate.of(year, 1, 1);
    }

    public static LocalDate yearEnd(int year) {
        return LocalDate.of(year, 12, 31);
    }

    /** "Today" in the business timezone, not the JVM's. */
    public LocalDate today() {
        return LocalDate.now(ZoneId.of(attendanceProperties.getZone()));
    }

    /** The days of [start, end] that fall in each leave year (a half day is always a single date). */
    public static Map<Integer, BigDecimal> daysByYear(LocalDate start, LocalDate end, BigDecimal totalDays) {
        Map<Integer, BigDecimal> out = new TreeMap<>();
        if (leaveYearOf(start) == leaveYearOf(end)) {
            out.put(leaveYearOf(start), totalDays);
            return out;
        }
        for (int y = leaveYearOf(start); y <= leaveYearOf(end); y++) {
            LocalDate from = start.isAfter(yearStart(y)) ? start : yearStart(y);
            LocalDate to = end.isBefore(yearEnd(y)) ? end : yearEnd(y);
            out.put(y, BigDecimal.valueOf(ChronoUnit.DAYS.between(from, to) + 1));
        }
        return out;
    }

    /** The days of the request in {@code year} dated on/before {@code cutoff} (null = all of them). */
    static BigDecimal daysOnOrBefore(LocalDate start, LocalDate end, BigDecimal totalDays, int year, LocalDate cutoff) {
        BigDecimal inYear = daysByYear(start, end, totalDays).getOrDefault(year, ZERO);
        if (cutoff == null) return inYear;
        LocalDate from = start.isAfter(yearStart(year)) ? start : yearStart(year);
        LocalDate to = end.isBefore(yearEnd(year)) ? end : yearEnd(year);
        if (cutoff.isBefore(to)) to = cutoff;
        if (to.isBefore(from)) return ZERO;
        if (start.equals(end)) return inYear;
        return BigDecimal.valueOf(ChronoUnit.DAYS.between(from, to) + 1);
    }

    // ── Entitlement ─────────────────────────────────────────────────────────────────────────────

    /** Policy days in force for {@code year}; empty for unpaid (unlimited). A paid type with no policy has 0. */
    public Optional<BigDecimal> entitlementDays(LeaveType type, int year) {
        if (!type.isPaid()) return Optional.empty();
        return Optional.of(policyRepository
                .findFirstByLeaveTypeIdAndEffectiveYearLessThanEqualOrderByEffectiveYearDesc(type.getId(), year)
                .map(LeaveEntitlementPolicy::getDays)
                .orElse(ZERO));
    }

    /**
     * The (employee, type, year) balance, created (0 credited) if it doesn't exist yet and brought up
     * to date with every credit due by today, so new hires and new types are credited immediately.
     */
    @Transactional
    public LeaveBalance ensureBalance(UUID employeeUserId, LeaveType type, int year) {
        if (!type.isAnnualBalance()) {
            throw new IllegalArgumentException(type.getName() + " does not keep a yearly balance");
        }
        if (leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(employeeUserId, type.getId(), year).isEmpty()) {
            leaveBalanceRepository.insertIfAbsent(employeeUserId, type.getId(), year, ZERO);
        }
        accrualService.accrue(employeeUserId, type, year);
        return leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(employeeUserId, type.getId(), year)
                .orElseThrow();
    }

    /** Opening balances for every active regular paid type the employee is eligible for (new hires, new types). Idempotent. */
    @Transactional
    public void ensureBalances(UUID employeeUserId, int year) {
        for (LeaveType type : eligibleAnnualTypes(employeeUserId)) ensureBalance(employeeUserId, type, year);
    }

    private List<LeaveType> eligibleAnnualTypes(UUID employeeUserId) {
        return eligibilityService.eligibleTypes(employeeUserId, leaveTypeRepository.findByActiveTrueOrderByNameAsc().stream()
                .filter(LeaveType::isAnnualBalance).toList());
    }

    // ── Position (what's left) ──────────────────────────────────────────────────────────────────

    /** One balance row with its pending reservations, as of today. */
    public record AnnualPosition(LeaveBalance balance, BigDecimal currentAvailable, BigDecimal carriedForwardAvailable,
                                 BigDecimal pendingDays, BigDecimal pendingCarryForwardEligible, boolean carryForwardOpen,
                                 LeaveAccrualService.Projection projection) {
        /** Carried-forward days still usable: all of them before expiry; afterwards only what pending leave dated before expiry claims. */
        public BigDecimal usableCarryForward() {
            return carryForwardOpen ? carriedForwardAvailable : carriedForwardAvailable.min(pendingCarryForwardEligible);
        }

        /** From credited days only; negative when leave was taken in advance. */
        public BigDecimal remaining() {
            return currentAvailable.add(usableCarryForward()).subtract(pendingDays);
        }

        /** Credits still to come this year, which a request may already use. */
        public BigDecimal upcomingCredits() {
            return projection.upcomingDays();
        }

        public BigDecimal availableToApply() {
            return remaining().add(upcomingCredits());
        }
    }

    public AnnualPosition position(LeaveBalance b, UUID excludeRequestId) {
        int year = b.getYear();
        LocalDate cutoff = b.getCarriedForwardExpiresOn();
        BigDecimal pending = ZERO;
        BigDecimal pendingEligible = ZERO;
        for (LeaveRequest r : leaveRequestRepository.findPendingOverlapping(
                b.getEmployeeUserId(), b.getLeaveType().getId(), yearStart(year), yearEnd(year))) {
            if (r.getId().equals(excludeRequestId)) continue;
            pending = pending.add(daysByYear(r.getStartDate(), r.getEndDate(), r.getTotalDays()).getOrDefault(year, ZERO));
            pendingEligible = pendingEligible.add(daysOnOrBefore(r.getStartDate(), r.getEndDate(), r.getTotalDays(), year, cutoff));
        }
        return new AnnualPosition(b,
                b.getTotalDays().subtract(b.getUsedDays()),
                carriedForwardAvailable(b),
                pending,
                pendingEligible,
                cutoff == null || !today().isAfter(cutoff),
                accrualService.project(b.getEmployeeUserId(), b.getLeaveType(), year));
    }

    static BigDecimal carriedForwardAvailable(LeaveBalance b) {
        return b.getCarriedForwardDays().subtract(b.getCarriedForwardUsedDays())
                .subtract(b.getCarriedForwardExpiredDays()).max(ZERO);
    }

    /** Remaining entitlement of the occurrence a request starting on {@code date} would draw from. */
    public record OccurrencePosition(LeaveOccurrence occurrence, BigDecimal entitlement, BigDecimal used, BigDecimal pending) {
        public BigDecimal remaining() {
            return entitlement.subtract(used).subtract(pending);
        }
    }

    public OccurrencePosition occurrencePosition(UUID employeeUserId, LeaveType type, LocalDate date, UUID excludeRequestId) {
        List<LeaveRequest> pendings = leaveRequestRepository.findPendingOverlapping(employeeUserId, type.getId(), FAR_PAST, FAR_FUTURE)
                .stream().filter(r -> !r.getId().equals(excludeRequestId)).toList();
        Optional<LeaveOccurrence> covering = occurrenceRepository.findCovering(employeeUserId, type.getId(), date);
        if (covering.isPresent()) {
            LeaveOccurrence o = covering.get();
            BigDecimal pending = sum(pendings.stream()
                    .filter(r -> !r.getStartDate().isBefore(o.getStartDate()) && !r.getStartDate().isAfter(o.getWindowEndDate())));
            return new OccurrencePosition(o, o.getEntitlementDays(), o.getUsedDays(), pending);
        }
        // No occurrence yet: a new one would open at `date`. Pending requests not inside an existing
        // occurrence and within one window of `date` would share it, so they are reserved against it.
        int months = windowMonths(type);
        BigDecimal pending = sum(pendings.stream()
                .filter(r -> occurrenceRepository.findCovering(employeeUserId, type.getId(), r.getStartDate()).isEmpty())
                .filter(r -> r.getStartDate().isAfter(date.minusMonths(months)) && r.getStartDate().isBefore(date.plusMonths(months))));
        return new OccurrencePosition(null, entitlementDays(type, leaveYearOf(date)).orElse(ZERO), ZERO, pending);
    }

    private static BigDecimal sum(java.util.stream.Stream<LeaveRequest> requests) {
        return requests.map(LeaveRequest::getTotalDays).reduce(ZERO, BigDecimal::add);
    }

    private static int windowMonths(LeaveType type) {
        if (type.getOccurrenceWindowMonths() == null) {
            throw new IllegalStateException(type.getName() + " has no occurrence window configured");
        }
        return type.getOccurrenceWindowMonths();
    }

    // ── Request validation and approval ─────────────────────────────────────────────────────────

    /**
     * Submission/edit check: can [start, end] still be covered? Each leave year a request touches is
     * checked against that year's own balance; carried-forward days count only for dates on/before
     * their expiry. {@code excludeRequestId} keeps an edited request from reserving against itself.
     */
    @Transactional
    public void requireAvailable(UUID employeeUserId, LeaveType type, LocalDate start, LocalDate end,
                                 BigDecimal totalDays, UUID excludeRequestId) {
        if (!type.isPaid()) return;
        if (type.isPerOccurrence()) {
            OccurrencePosition p = occurrencePosition(employeeUserId, type, start, excludeRequestId);
            if (p.remaining().compareTo(totalDays) < 0) {
                throw new IllegalArgumentException("Leave request exceeds your available " + type.getName()
                        + " entitlement of " + formatDays(p.remaining().max(ZERO)) + " days for this occurrence.");
            }
            return;
        }
        Map<Integer, BigDecimal> segments = daysByYear(start, end, totalDays);
        for (Map.Entry<Integer, BigDecimal> seg : segments.entrySet()) {
            int year = seg.getKey();
            LeaveBalance b = ensureBalance(employeeUserId, type, year);
            AnnualPosition p = position(b, excludeRequestId);
            BigDecimal eligible = daysOnOrBefore(start, end, totalDays, year, b.getCarriedForwardExpiresOn());
            BigDecimal demand = p.pendingDays().add(seg.getValue());
            BigDecimal supply = p.currentAvailable()
                    .add(p.upcomingCredits())
                    .add(p.carriedForwardAvailable().min(p.pendingCarryForwardEligible().add(eligible)));
            if (supply.compareTo(demand) < 0) {
                BigDecimal left = supply.subtract(p.pendingDays()).max(ZERO);
                throw new IllegalArgumentException("Leave request exceeds your available " + type.getName() + " balance"
                        + (segments.size() > 1 ? " for " + year : "") + " of " + formatDays(left) + " days.");
            }
        }
    }

    /**
     * Approval: deducts the request. Per leave year, carried-forward days (only for dates on/before
     * expiry) and current-year days are used in the type's configured priority. Throws
     * IllegalStateException when the days no longer fit (the caller's transaction rolls back).
     */
    @Transactional
    public void consume(UUID employeeUserId, LeaveType type, LocalDate start, LocalDate end, BigDecimal totalDays) {
        if (!type.isPaid()) return;
        if (type.isPerOccurrence()) {
            LeaveOccurrence o = occurrenceRepository.findCovering(employeeUserId, type.getId(), start)
                    .orElseGet(() -> openOccurrence(employeeUserId, type, start));
            if (o.getEntitlementDays().subtract(o.getUsedDays()).compareTo(totalDays) < 0) {
                throw insufficient();
            }
            o.setUsedDays(o.getUsedDays().add(totalDays));
            occurrenceRepository.save(o);
            return;
        }
        for (Map.Entry<Integer, BigDecimal> seg : daysByYear(start, end, totalDays).entrySet()) {
            LeaveBalance b = ensureBalance(employeeUserId, type, seg.getKey());
            BigDecimal days = seg.getValue();
            BigDecimal eligible = daysOnOrBefore(start, end, totalDays, seg.getKey(), b.getCarriedForwardExpiresOn()).min(days);
            BigDecimal cfAvailable = carriedForwardAvailable(b);
            BigDecimal current = b.getTotalDays().subtract(b.getUsedDays());
            BigDecimal fromCf;
            if (LeaveType.CURRENT_FIRST.equals(type.getCarryForwardPriority())) {
                // Days after expiry can only come from the current year; whatever current is left covers
                // the eligible days next, and only the rest falls back to carried-forward.
                BigDecimal currentLeftForEligible = current.subtract(days.subtract(eligible)).max(ZERO);
                fromCf = cfAvailable.min(eligible.subtract(currentLeftForEligible).max(ZERO));
            } else {
                fromCf = cfAvailable.min(eligible);
            }
            BigDecimal fromCurrent = days.subtract(fromCf);
            // Credits still to come this year may be used in advance (used_days then exceeds total_days).
            BigDecimal upcoming = accrualService.project(employeeUserId, type, seg.getKey()).upcomingDays();
            if (fromCurrent.compareTo(current.add(upcoming)) > 0) {
                throw insufficient();
            }
            b.setCarriedForwardUsedDays(b.getCarriedForwardUsedDays().add(fromCf));
            b.setUsedDays(b.getUsedDays().add(fromCurrent));
            leaveBalanceRepository.save(b);
        }
    }

    private static IllegalStateException insufficient() {
        return new IllegalStateException("Employee no longer has sufficient balance to approve this request");
    }

    // ponytail: two first-ever approvals for the same employee racing could open two occurrences;
    // approvals are human-paced. Add a per-employee advisory lock if that ever matters.
    private LeaveOccurrence openOccurrence(UUID employeeUserId, LeaveType type, LocalDate start) {
        return occurrenceRepository.save(LeaveOccurrence.builder()
                .employeeUserId(employeeUserId)
                .leaveType(type)
                .startDate(start)
                .windowEndDate(start.plusMonths(windowMonths(type)).minusDays(1))
                .entitlementDays(entitlementDays(type, leaveYearOf(start)).orElse(ZERO))
                .build());
    }

    /** Paid days still available across every active regular paid type for {@code year} (the "use paid leave first" rule). */
    @Transactional
    public BigDecimal availablePaidDays(UUID employeeUserId, int year, UUID excludeRequestId) {
        BigDecimal total = ZERO;
        for (LeaveType type : eligibleAnnualTypes(employeeUserId)) {
            total = total.add(position(ensureBalance(employeeUserId, type, year), excludeRequestId).remaining().max(ZERO));
        }
        return total;
    }

    // ── Attendance penalties ────────────────────────────────────────────────────────────────────

    /**
     * Penalty debit of up to {@code max} days from this type's balance for the incident's leave year,
     * carried-forward first while it still covers the incident date. Like before V310, pending
     * reservations are not considered. Returns the days actually debited.
     */
    @Transactional
    public BigDecimal debitUpTo(UUID employeeUserId, LeaveType type, LocalDate date, BigDecimal max) {
        if (!type.isAnnualBalance() || max == null || max.signum() <= 0) return ZERO;
        if (!eligibilityService.isEligible(employeeUserId, type)) return ZERO; // falls through to the next type or LOP
        LeaveBalance b = ensureBalance(employeeUserId, type, leaveYearOf(date));
        LocalDate cutoff = b.getCarriedForwardExpiresOn();
        BigDecimal cfAvailable = cutoff == null || !date.isAfter(cutoff) ? carriedForwardAvailable(b) : ZERO;
        BigDecimal fromCf = cfAvailable.min(max);
        BigDecimal fromCurrent = b.getTotalDays().subtract(b.getUsedDays()).max(ZERO).min(max.subtract(fromCf));
        if (fromCf.signum() == 0 && fromCurrent.signum() == 0) return ZERO;
        b.setCarriedForwardUsedDays(b.getCarriedForwardUsedDays().add(fromCf));
        b.setUsedDays(b.getUsedDays().add(fromCurrent));
        leaveBalanceRepository.save(b);
        return fromCf.add(fromCurrent);
    }

    /**
     * Reverses a penalty debit. Debits draw on carried-forward first, so the credit goes back there
     * first, then to the current year. Returns false when there is no balance row to credit.
     */
    // ponytail: a debit's exact bucket split isn't recorded, so a later approval in between can shift
    // which bucket gets the credit back; the total is always exact. Store the split if expiry disputes arise.
    @Transactional
    public boolean credit(UUID employeeUserId, LeaveType type, int year, BigDecimal days) {
        Optional<LeaveBalance> row = leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(employeeUserId, type.getId(), year);
        row.ifPresent(b -> {
            BigDecimal toCf = b.getCarriedForwardUsedDays().min(days);
            b.setCarriedForwardUsedDays(b.getCarriedForwardUsedDays().subtract(toCf));
            b.setUsedDays(b.getUsedDays().subtract(days.subtract(toCf)).max(ZERO));
            leaveBalanceRepository.save(b);
        });
        return row.isPresent();
    }

    // ── Display ─────────────────────────────────────────────────────────────────────────────────

    /**
     * An employee's balances for {@code year}: every regular paid type (rows created on first view),
     * every active per-occurrence type, and every active unpaid type as "unlimited". Inactive types
     * still show if the employee already has a balance row for them.
     */
    @Transactional
    public List<LeaveBalanceResponse> balancesFor(UUID employeeUserId, int year) {
        return balancesFor(employeeUserId, year, false);
    }

    /**
     * {@code includeInactive}: the admin view also lists balances the employee still holds on
     * deactivated types (flagged {@code leaveTypeActive = false}) so HR can correct them; the
     * employee's own view never shows a deactivated type.
     */
    @Transactional
    public List<LeaveBalanceResponse> balancesFor(UUID employeeUserId, int year, boolean includeInactive) {
        Map<UUID, LeaveBalance> rows = leaveBalanceRepository.findByEmployeeUserIdAndYear(employeeUserId, year).stream()
                .collect(Collectors.toMap(b -> b.getLeaveType().getId(), Function.identity(), (a, b) -> a));
        List<LeaveType> all = leaveTypeRepository.findAllByOrderByNameAsc();
        Set<UUID> eligible = eligibilityService.eligibleTypes(employeeUserId, all).stream().map(LeaveType::getId)
                .collect(Collectors.toSet());
        List<LeaveBalanceResponse> out = new ArrayList<>();
        for (LeaveType type : all) {
            boolean canUse = type.isActive() && eligible.contains(type.getId());
            if (type.isAnnualBalance()) {
                LeaveBalance b = rows.get(type.getId());
                if (canUse) {
                    b = ensureBalance(employeeUserId, type, year); // also catches up any credit due
                } else if (b == null || isEmpty(b) || (!type.isActive() && !includeInactive)) {
                    // A balance kept after losing eligibility stays visible unless empty; a deactivated
                    // type is hidden from the employee and only listed for HR.
                    continue;
                }
                out.add(toResponse(b));
            } else if (canUse) {
                out.add(type.isPerOccurrence() ? occurrenceResponse(employeeUserId, type) : unlimitedResponse(type, year));
            }
        }
        return out;
    }

    /**
     * Read-only view of a leave year that hasn't started (YE-04): existing rows as they are, and for a
     * type with no row yet the expected opening position — 0 credited, the year's quota still to come,
     * less pending leave already requested in that year. Nothing is created or credited.
     */
    @Transactional(readOnly = true)
    public List<LeaveBalanceResponse> expectedBalancesFor(UUID employeeUserId, int year) {
        Map<UUID, LeaveBalance> rows = leaveBalanceRepository.findByEmployeeUserIdAndYear(employeeUserId, year).stream()
                .collect(Collectors.toMap(b -> b.getLeaveType().getId(), Function.identity(), (a, b) -> a));
        List<LeaveType> all = leaveTypeRepository.findByActiveTrueOrderByNameAsc();
        Set<UUID> eligible = eligibilityService.eligibleTypes(employeeUserId, all).stream().map(LeaveType::getId)
                .collect(Collectors.toSet());
        List<LeaveBalanceResponse> out = new ArrayList<>();
        for (LeaveType type : all) {
            if (!eligible.contains(type.getId())) continue;
            if (type.isAnnualBalance()) {
                LeaveBalance b = rows.getOrDefault(type.getId(), LeaveBalance.builder()
                        .employeeUserId(employeeUserId).leaveType(type).year(year)
                        .totalDays(ZERO).usedDays(ZERO).carriedForwardDays(ZERO).carriedForwardUsedDays(ZERO)
                        .carriedForwardExpiredDays(ZERO).build());
                LeaveBalanceResponse r = toResponse(b);
                r.setExpected(true);
                out.add(r);
            } else {
                out.add(type.isPerOccurrence() ? occurrenceResponse(employeeUserId, type) : unlimitedResponse(type, year));
            }
        }
        return out;
    }

    private static boolean isEmpty(LeaveBalance b) {
        return b.getTotalDays().signum() == 0 && b.getUsedDays().signum() == 0 && b.getCarriedForwardDays().signum() == 0;
    }

    /** Existing regular-paid balance rows of several employees (My Team) — read-only, nothing created. */
    @Transactional(readOnly = true)
    public List<LeaveBalanceResponse> existingBalances(Collection<UUID> employeeUserIds, int year) {
        return leaveBalanceRepository.findByEmployeeUserIdInAndYear(employeeUserIds, year).stream()
                .filter(b -> b.getLeaveType().isAnnualBalance() && b.getLeaveType().isActive())
                .map(b -> {
                    LeaveBalanceResponse r = toResponse(b);
                    r.setEmployeeUserId(b.getEmployeeUserId());
                    return r;
                })
                .toList();
    }

    public LeaveBalanceResponse toResponse(LeaveBalance b) {
        AnnualPosition p = position(b, null);
        LeaveType t = b.getLeaveType();
        return LeaveBalanceResponse.builder()
                .balanceId(b.getId())
                .leaveTypeId(t.getId())
                .leaveTypeCode(t.getCode())
                .leaveTypeName(t.getName())
                .leaveTypeActive(t.isActive())
                .classification(t.getClassification())
                .entitlementBasis(LeaveType.BASIS_ANNUAL)
                .year(b.getYear())
                .totalDays(b.getTotalDays())
                .usedDays(b.getUsedDays().add(b.getCarriedForwardUsedDays()))
                .pendingDays(p.pendingDays())
                .remainingDays(p.remaining())
                .carriedForwardDays(b.getCarriedForwardDays())
                .carriedForwardUsedDays(b.getCarriedForwardUsedDays())
                .carriedForwardExpiredDays(b.getCarriedForwardExpiredDays())
                .carriedForwardRemainingDays(p.usableCarryForward())
                .carriedForwardExpiresOn(b.getCarriedForwardExpiresOn())
                .creditFrequency(p.projection().frequency())
                .annualQuotaDays(b.getTotalDays().add(p.upcomingCredits()))
                .upcomingCreditDays(p.upcomingCredits())
                .availableToApplyDays(p.availableToApply())
                .nextCreditDate(p.projection().nextCreditDate())
                .nextCreditDays(p.projection().nextCreditDays())
                .build();
    }

    private LeaveBalanceResponse occurrenceResponse(UUID employeeUserId, LeaveType type) {
        LocalDate today = today();
        // The occurrence still open today (it may have started in the past or be scheduled to start soon).
        LeaveOccurrence current = occurrenceRepository.findByEmployeeUserIdAndLeaveTypeIdOrderByStartDateDesc(employeeUserId, type.getId())
                .stream().filter(o -> !o.getWindowEndDate().isBefore(today)).findFirst().orElse(null);
        OccurrencePosition p = occurrencePosition(employeeUserId, type, current != null ? current.getStartDate() : today, null);
        return LeaveBalanceResponse.builder()
                .leaveTypeId(type.getId())
                .leaveTypeCode(type.getCode())
                .leaveTypeName(type.getName())
                .classification(type.getClassification())
                .entitlementBasis(LeaveType.BASIS_PER_OCCURRENCE)
                .year(leaveYearOf(today))
                .totalDays(p.entitlement())
                .usedDays(p.used())
                .pendingDays(p.pending())
                .remainingDays(p.remaining())
                .occurrenceStartDate(current != null ? current.getStartDate() : null)
                .occurrenceWindowEndDate(current != null ? current.getWindowEndDate() : null)
                .build();
    }

    private static LeaveBalanceResponse unlimitedResponse(LeaveType type, int year) {
        return LeaveBalanceResponse.builder()
                .leaveTypeId(type.getId())
                .leaveTypeCode(type.getCode())
                .leaveTypeName(type.getName())
                .classification(type.getClassification())
                .entitlementBasis("UNLIMITED")
                .unlimited(true)
                .year(year)
                .build();
    }

    public static String formatDays(BigDecimal days) {
        return days.stripTrailingZeros().toPlainString();
    }
}
