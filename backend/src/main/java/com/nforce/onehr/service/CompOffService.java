package com.nforce.onehr.service;

import com.nforce.onehr.dto.compoff.CompOffDtos.AdjustRequest;
import com.nforce.onehr.dto.compoff.CompOffDtos.BalanceDto;
import com.nforce.onehr.dto.compoff.CompOffDtos.ClaimDto;
import com.nforce.onehr.dto.compoff.CompOffDtos.CreditDto;
import com.nforce.onehr.dto.compoff.CompOffDtos.DecisionRequest;
import com.nforce.onehr.dto.compoff.CompOffDtos.DetectRequest;
import com.nforce.onehr.dto.compoff.CompOffDtos.DetectResult;
import com.nforce.onehr.dto.compoff.CompOffDtos.EventDto;
import com.nforce.onehr.dto.compoff.CompOffDtos.MyCompOff;
import com.nforce.onehr.dto.compoff.CompOffDtos.SkippedRecord;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.CompOffClaim;
import com.nforce.onehr.entity.CompOffCredit;
import com.nforce.onehr.entity.CompOffEvent;
import com.nforce.onehr.entity.CompOffSettings;
import com.nforce.onehr.entity.CompOffUtilization;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.EmployeeShiftAssignment;
import com.nforce.onehr.entity.Holiday;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.OtCalculation;
import com.nforce.onehr.entity.OvertimeRequest;
import com.nforce.onehr.entity.ShiftVersion;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.CompOffClaimRepository;
import com.nforce.onehr.repository.CompOffCreditRepository;
import com.nforce.onehr.repository.CompOffEventRepository;
import com.nforce.onehr.repository.CompOffUtilizationRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.HolidayRepository;
import com.nforce.onehr.repository.LeaveRequestRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.OtCalculationRepository;
import com.nforce.onehr.repository.OvertimeRequestRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Comp-Off earning and the credit ledger.
 *
 * <pre>
 *   eligibleMinutes = worked                       (weekly off / holiday, when the policy covers that day type)
 *                   = worked - scheduled shift     (weekday, only when the policy includes weekday extra hours)
 *   credit          = 1 day   when eligibleMinutes >= full-day minimum
 *                   = 0.5 day when eligibleMinutes >= half-day minimum
 *                   = none    otherwise
 * </pre>
 * Every eligible day becomes one {@link CompOffClaim} (unique per employee and date). When both OT and
 * Comp-Off are enabled the employee picks one per claim and the choice is final; when only Comp-Off is
 * enabled the credit is issued automatically. Credits are consumed oldest-expiry first, by approved
 * Comp-Off leave (see {@link #validateLeaveRequest}/{@link #consumeForApprovedLeave}) or by an HR deduction.
 */
@Service
@RequiredArgsConstructor
public class CompOffService {

    public static final String LEAVE_TYPE_CODE = "COMP_OFF";
    private static final String APPROVE_ALL = "leave:APPROVE:ALL";
    private static final String APPROVE_TEAM = "leave:APPROVE:TEAM";
    private static final int MAX_RANGE_DAYS = 62;
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final LocalDate FAR_PAST = LocalDate.of(1900, 1, 1);
    private static final LocalDate FAR_FUTURE = LocalDate.of(2999, 12, 31);

    private final CompOffPolicyService policyService;
    private final CompOffClaimRepository claimRepository;
    private final CompOffCreditRepository creditRepository;
    private final CompOffUtilizationRepository utilizationRepository;
    private final CompOffEventRepository eventRepository;
    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final EmployeeShiftAssignmentResolver shiftAssignmentResolver;
    private final ShiftVersionResolver shiftVersionResolver;
    private final WorkingDayService workingDayService;
    private final HolidayRepository holidayRepository;
    private final OtCalculationRepository otCalculationRepository;
    private final OvertimeRequestRepository overtimeRequestRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final UserRepository userRepository;
    private final EffectivePermissionService permissionService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    // ------------------------------------------------------------------ eligibility

    /** HR-triggered detection over a date range (also used by the nightly job and the OT run). */
    @Transactional
    public DetectResult detect(DetectRequest req, String actorEmail) {
        User actor = actor(actorEmail);
        if (req.to().isBefore(req.from())) throw new IllegalArgumentException("'To' date cannot be before 'from' date");
        if (req.from().plusDays(MAX_RANGE_DAYS).isBefore(req.to())) {
            throw new IllegalArgumentException("Range cannot exceed " + MAX_RANGE_DAYS + " days");
        }
        if (req.to().isAfter(LocalDate.now())) throw new IllegalArgumentException("Cannot detect Comp-Off for future dates");
        if (!policyService.compOffEnabled()) throw new IllegalStateException("Comp-Off is not enabled by company policy");
        DetectResult result = detect(req.from(), req.to(), req.employeeIds(), actor.getId());
        auditService.log(actor.getId(), "COMP_OFF_DETECTION_RUN", actor.getId());
        return result;
    }

    /** Creates a claim for every eligible attendance day that has none yet. Idempotent. */
    @Transactional
    public DetectResult detect(LocalDate from, LocalDate to, Collection<UUID> employeeIds, UUID actorId) {
        CompOffSettings settings = policyService.settings();
        if (!settings.isCompOffEnabled()) return new DetectResult(0, 0, 0, List.of());

        List<Attendance> records = new ArrayList<>();
        if (employeeIds != null && !employeeIds.isEmpty()) {
            records.addAll(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.copyOf(employeeIds), from, to));
        } else {
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) records.addAll(attendanceRepository.findByWorkDate(d));
        }
        Set<UUID> ids = records.stream().map(Attendance::getEmployeeUserId).collect(Collectors.toSet());
        if (ids.isEmpty()) return new DetectResult(0, 0, 0, List.of());

        Map<UUID, Employee> employees = employeeRepository.findAllByIdWithDepartment(ids).stream()
                .collect(Collectors.toMap(Employee::getUserId, e -> e));
        WorkingDayService.WeeklyOffBatchResolver weeklyOff = workingDayService.weeklyOffDaysResolver(ids);
        Set<UUID> locationIds = employees.values().stream()
                .filter(e -> e.getLocation() != null).map(e -> e.getLocation().getId()).collect(Collectors.toSet());
        Map<UUID, Set<LocalDate>> holidays = new HashMap<>();
        if (!locationIds.isEmpty()) {
            for (Holiday h : holidayRepository.findByLocation_IdInAndActiveTrue(locationIds)) {
                holidays.computeIfAbsent(h.getLocation().getId(), k -> new HashSet<>()).add(h.getHolidayDate());
            }
        }
        Set<String> existing = claimRepository.findByEmployeeUserIdInAndWorkDateBetween(ids, from, to).stream()
                .map(c -> key(c.getEmployeeUserId(), c.getWorkDate())).collect(Collectors.toSet());

        int created = 0, auto = 0, unchanged = 0;
        List<SkippedRecord> skipped = new ArrayList<>();
        for (Attendance att : records) {
            Employee emp = employees.get(att.getEmployeeUserId());
            LocalDate date = att.getWorkDate();
            if (emp == null || date.isBefore(from) || date.isAfter(to)) continue;
            if (existing.contains(key(emp.getUserId(), date))) { unchanged++; continue; }

            String dayType = OtCalculationService.dayType(emp, date, weeklyOff, holidays);
            if (("WEEKLY_OFF".equals(dayType) && !settings.isEligibleWeeklyOff())
                    || ("HOLIDAY".equals(dayType) && !settings.isEligibleHoliday())
                    || ("WEEKDAY".equals(dayType) && !settings.isIncludeWeekdayExtra())) continue;

            String invalid = OtCalculationService.validateAttendance(att);
            if (invalid != null) {
                skipped.add(new SkippedRecord(emp.getUserId(), emp.getFullName(), date, invalid));
                continue;
            }
            int worked = att.getWorkedMinutes() != null && att.getWorkedMinutes() > 0 ? att.getWorkedMinutes()
                    : (int) Duration.between(att.getCheckInAt(), att.getCheckOutAt()).toMinutes();
            int eligible = worked;
            if ("WEEKDAY".equals(dayType)) {
                Optional<EmployeeShiftAssignment> assignment = shiftAssignmentResolver.resolveIfPresent(emp.getUserId(), date);
                if (assignment.isEmpty()) {
                    skipped.add(new SkippedRecord(emp.getUserId(), emp.getFullName(), date, "No shift assigned"));
                    continue;
                }
                Optional<ShiftVersion> version = shiftVersionResolver.resolveIfPresent(assignment.get().getShift(), date);
                if (version.isEmpty()) {
                    skipped.add(new SkippedRecord(emp.getUserId(), emp.getFullName(), date, "Shift has no timings for this date"));
                    continue;
                }
                eligible = worked - OtCalculationService.scheduledMinutes(version.get());
            }
            BigDecimal credit = creditFor(settings, eligible);
            if (credit.signum() == 0) continue; // below the minimum working hours for Comp-Off

            CompOffClaim claim = claimRepository.save(CompOffClaim.builder()
                    .employeeUserId(emp.getUserId()).workDate(date).dayType(dayType)
                    .workedMinutes(worked).eligibleMinutes(eligible).creditDays(credit).build());
            created++;
            if (!settings.isOvertimeEnabled()) {
                award(claim, settings, emp.getUserId(), actorId);
                auto++;
            } else {
                notificationService.send(emp.getUserId(), "COMP_OFF_CHOICE", "Choose OT or Comp-Off",
                        "You worked on " + date + ". Choose whether to receive overtime or " + credit.stripTrailingZeros().toPlainString()
                                + " day Comp-Off for it.", "/comp-off");
            }
        }
        return new DetectResult(created, auto, unchanged, skipped);
    }

    public static BigDecimal creditFor(CompOffSettings s, int eligibleMinutes) {
        if (eligibleMinutes >= s.getMinMinutesFullDay()) return BigDecimal.ONE;
        if (eligibleMinutes >= s.getMinMinutesHalfDay()) return HALF;
        return BigDecimal.ZERO;
    }

    // ------------------------------------------------------------------ OT or Comp-Off

    /** The employee's one-time decision for an eligible day. */
    @Transactional
    public ClaimDto choose(UUID claimId, String choice, String actorEmail) {
        User actor = actor(actorEmail);
        CompOffClaim claim = claimRepository.findById(claimId).orElseThrow(() -> new IllegalArgumentException("Claim not found"));
        if (!claim.getEmployeeUserId().equals(actor.getId())) throw new AccessDeniedException("You can only decide your own working days");
        if (!CompOffClaim.PENDING.equals(claim.getChoice())) {
            throw new IllegalStateException("This day is already settled as " + ("OT".equals(claim.getChoice()) ? "overtime" : "Comp-Off"));
        }
        CompOffSettings settings = policyService.settings();
        if (CompOffClaim.OT.equals(choice)) {
            if (!settings.isOvertimeEnabled()) throw new IllegalStateException("Overtime is not enabled by company policy");
            claim.setChoice(CompOffClaim.OT);
        } else {
            if (!settings.isCompOffEnabled()) throw new IllegalStateException("Comp-Off is not enabled by company policy");
            assertNoOvertimeClaim(claim);
            award(claim, settings, actor.getId(), actor.getId());
            return toClaimDto(claim, null, claimCredit(claim));
        }
        claim.setChosenAt(LocalDateTime.now());
        claim.setChosenBy(actor.getId());
        claimRepository.save(claim);
        event(claim.getEmployeeUserId(), null, "OT_SELECTED", null, "Overtime selected for " + claim.getWorkDate(), actor.getId());
        auditService.log(actor.getId(), "COMP_OFF_OT_SELECTED", claim.getId());
        return toClaimDto(claim, null, null);
    }

    private void assertNoOvertimeClaim(CompOffClaim claim) {
        Optional<OtCalculation> calc = otCalculationRepository.findByEmployeeUserIdAndWorkDate(claim.getEmployeeUserId(), claim.getWorkDate());
        if (calc.isPresent() && !OtCalculation.REJECTED.equals(calc.get().getStatus())) {
            throw new IllegalStateException("Overtime has already been calculated for " + claim.getWorkDate()
                    + " - the same hours cannot also earn Comp-Off");
        }
        boolean otRequest = overtimeRequestRepository.findByEmployeeUserIdInAndWorkDateBetween(
                        List.of(claim.getEmployeeUserId()), claim.getWorkDate(), claim.getWorkDate()).stream()
                .anyMatch(r -> !"REJECTED".equals(r.getStatus()) && !"CANCELLED".equals(r.getStatus()));
        if (otRequest) {
            throw new IllegalStateException("An overtime request already exists for " + claim.getWorkDate()
                    + " - the same hours cannot also earn Comp-Off");
        }
    }

    /** Issues the credit for a claim and settles it as COMP_OFF. */
    private CompOffCredit award(CompOffClaim claim, CompOffSettings settings, UUID employeeId, UUID actorId) {
        LocalDate today = LocalDate.now();
        boolean needsApproval = settings.isRequireCreditApproval();
        CompOffCredit credit = creditRepository.save(CompOffCredit.builder()
                .employeeUserId(employeeId).claimId(claim.getId()).source(CompOffCredit.EARNED)
                .workDate(claim.getWorkDate()).creditDays(claim.getCreditDays())
                .status(needsApproval ? CompOffCredit.PENDING_APPROVAL : CompOffCredit.AVAILABLE)
                .earnedOn(today).expiresOn(today.plusDays(settings.getValidityDays()))
                .reason("Worked on " + claim.getWorkDate() + " (" + claim.getDayType().toLowerCase().replace('_', ' ') + ")").build());
        claim.setChoice(CompOffClaim.COMP_OFF);
        claim.setChosenAt(LocalDateTime.now());
        claim.setChosenBy(actorId);
        claim.setCreditId(credit.getId());
        claimRepository.save(claim);
        event(employeeId, credit.getId(), "CREDIT_GENERATED", credit.getCreditDays(),
                "Comp-Off for " + claim.getWorkDate() + (needsApproval ? " (awaiting approval)" : ""), actorId);
        auditService.log(actorId != null ? actorId : employeeId, "COMP_OFF_CREDIT_GENERATED", credit.getId());
        if (needsApproval) notifyApprovers(employeeId, credit);
        return credit;
    }

    private void notifyApprovers(UUID employeeId, CompOffCredit credit) {
        Set<UUID> recipients = new HashSet<>();
        historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)
                .map(EmployeeManagerHistory::getManagerUserId).ifPresent(recipients::add);
        if (recipients.isEmpty()) recipients.addAll(permissionService.activeUserIdsWithAuthority("leave", "APPROVE", "ALL"));
        recipients.remove(employeeId);
        String message = employeeName(employeeId) + " earned " + credit.getCreditDays().stripTrailingZeros().toPlainString()
                + " day Comp-Off for " + credit.getWorkDate() + ". It needs your approval.";
        for (UUID r : recipients) {
            notificationService.send(r, "COMP_OFF_APPROVAL", "Comp-Off awaiting approval", message, "/comp-off-management");
        }
    }

    // ------------------------------------------------------------------ credit approval

    @Transactional(readOnly = true)
    public List<CreditDto> pendingApprovals(String actorEmail) {
        User actor = actor(actorEmail);
        List<CompOffCredit> pending = creditRepository.findByStatus(CompOffCredit.PENDING_APPROVAL);
        if (!permissionService.hasAuthority(actor, APPROVE_ALL)) {
            Set<UUID> team = new HashSet<>(historyRepository.findCurrentDirectReportIds(actor.getId()));
            pending = pending.stream().filter(c -> team.contains(c.getEmployeeUserId())).toList();
        }
        return toCreditDtos(pending);
    }

    @Transactional
    public CreditDto decide(UUID creditId, DecisionRequest req, String actorEmail) {
        User actor = actor(actorEmail);
        boolean approve = "APPROVE".equals(req.decision());
        if (!approve && (req.comment() == null || req.comment().isBlank())) {
            throw new IllegalArgumentException("A comment is required when rejecting a Comp-Off credit");
        }
        CompOffCredit credit = creditRepository.findById(creditId).orElseThrow(() -> new IllegalArgumentException("Credit not found"));
        requireApprover(actor, credit.getEmployeeUserId());
        if (!CompOffCredit.PENDING_APPROVAL.equals(credit.getStatus())) throw new IllegalStateException("Credit has already been decided");
        credit.setStatus(approve ? CompOffCredit.AVAILABLE : CompOffCredit.REJECTED);
        credit.setReviewedBy(actor.getId());
        credit.setReviewedAt(LocalDateTime.now());
        credit.setReviewComment(req.comment());
        creditRepository.save(credit);
        event(credit.getEmployeeUserId(), credit.getId(), approve ? "CREDIT_APPROVED" : "CREDIT_REJECTED",
                credit.getCreditDays(), req.comment(), actor.getId());
        auditService.log(actor.getId(), approve ? "COMP_OFF_CREDIT_APPROVED" : "COMP_OFF_CREDIT_REJECTED", credit.getId());
        notificationService.send(credit.getEmployeeUserId(), approve ? "COMP_OFF_APPROVED" : "COMP_OFF_REJECTED",
                approve ? "Comp-Off credited" : "Comp-Off not approved",
                approve ? "Your Comp-Off for " + credit.getWorkDate() + " has been approved and added to your balance."
                        : "Your Comp-Off for " + credit.getWorkDate() + " was not approved. Reason: " + req.comment().trim(),
                "/comp-off");
        return toCreditDtos(List.of(credit)).get(0);
    }

    private void requireApprover(User actor, UUID employeeId) {
        if (actor.getId().equals(employeeId)) throw new AccessDeniedException("You cannot decide on your own Comp-Off");
        if (permissionService.hasAuthority(actor, APPROVE_ALL)) return;
        if (!permissionService.hasAuthority(actor, APPROVE_TEAM)) throw new AccessDeniedException("Missing " + APPROVE_TEAM);
        boolean manager = historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)
                .map(h -> h.getManagerUserId().equals(actor.getId())).orElse(false);
        if (!manager) throw new AccessDeniedException("You are not the current manager of this employee");
    }

    // ------------------------------------------------------------------ manual adjustments

    /** Audited HR correction: positive days add a credit, negative days remove available credit. */
    @Transactional
    public CreditDto adjust(AdjustRequest req, String actorEmail) {
        User actor = actor(actorEmail);
        BigDecimal days = req.days().setScale(2, java.math.RoundingMode.HALF_UP);
        if (days.signum() == 0) throw new IllegalArgumentException("Adjustment cannot be zero");
        if (days.abs().compareTo(new BigDecimal("30")) > 0) throw new IllegalArgumentException("Adjustment cannot exceed 30 days");
        if (!employeeRepository.existsById(req.employeeUserId())) throw new IllegalArgumentException("Employee not found");
        CompOffSettings settings = policyService.settings();
        LocalDate today = LocalDate.now();
        CompOffCredit result;
        if (days.signum() > 0) {
            result = creditRepository.save(CompOffCredit.builder()
                    .employeeUserId(req.employeeUserId()).source(CompOffCredit.MANUAL).creditDays(days)
                    .status(CompOffCredit.AVAILABLE).earnedOn(today).expiresOn(today.plusDays(settings.getValidityDays()))
                    .reason(req.reason().trim()).reviewedBy(actor.getId()).reviewedAt(LocalDateTime.now()).build());
            event(req.employeeUserId(), result.getId(), "ADJUSTED_ADD", days, req.reason().trim(), actor.getId());
        } else {
            List<CompOffCredit> touched = consume(req.employeeUserId(), days.abs(), today, null, true);
            result = touched.get(0);
            for (CompOffCredit c : touched) event(req.employeeUserId(), c.getId(), "ADJUSTED_DEDUCT", null, req.reason().trim(), actor.getId());
        }
        auditService.log(actor.getId(), "COMP_OFF_ADJUSTED", req.employeeUserId());
        notificationService.send(req.employeeUserId(), "COMP_OFF_ADJUSTED", "Comp-Off balance adjusted",
                "Your Comp-Off balance was adjusted by " + days.stripTrailingZeros().toPlainString() + " day(s). Reason: " + req.reason().trim(),
                "/comp-off");
        return toCreditDtos(List.of(result)).get(0);
    }

    // ------------------------------------------------------------------ expiry

    /** Expires lapsed credits and warns about ones about to lapse. Safe to run repeatedly. */
    @Transactional
    public int expireAndNotify(LocalDate today) {
        int expired = 0;
        for (CompOffCredit c : creditRepository.findByStatusAndExpiresOnBefore(CompOffCredit.AVAILABLE, today)) {
            BigDecimal left = c.remaining();
            if (left.signum() <= 0) continue; // fully spent; nothing to expire
            c.setExpiredDays(c.getExpiredDays().add(left));
            c.setStatus(CompOffCredit.EXPIRED);
            creditRepository.save(c);
            event(c.getEmployeeUserId(), c.getId(), "CREDIT_EXPIRED", left, "Validity ended on " + c.getExpiresOn(), null);
            notificationService.send(c.getEmployeeUserId(), "COMP_OFF_EXPIRED", "Comp-Off expired",
                    left.stripTrailingZeros().toPlainString() + " day(s) of Comp-Off expired on " + c.getExpiresOn() + ".", "/comp-off");
            expired++;
        }
        int notice = policyService.settings().getExpiryNoticeDays();
        for (CompOffCredit c : creditRepository.findByStatusAndExpiryNotifiedFalseAndExpiresOnBetween(
                CompOffCredit.AVAILABLE, today, today.plusDays(notice))) {
            if (c.remaining().signum() <= 0) continue;
            notificationService.send(c.getEmployeeUserId(), "COMP_OFF_EXPIRING", "Comp-Off expiring soon",
                    c.remaining().stripTrailingZeros().toPlainString() + " day(s) of Comp-Off expire on " + c.getExpiresOn()
                            + ". Apply for leave before then.", "/comp-off");
            c.setExpiryNotified(true);
            creditRepository.save(c);
        }
        return expired;
    }

    // ------------------------------------------------------------------ leave integration

    public boolean policyEnabled() {
        return policyService.compOffEnabled();
    }

    public static boolean isCompOffType(LeaveType type) {
        return type != null && LEAVE_TYPE_CODE.equals(type.getCode());
    }

    /** Days the employee can still request on {@code onDate}: valid credits minus in-flight Comp-Off requests. */
    @Transactional(readOnly = true)
    public BigDecimal availableForRequest(UUID employeeId, LocalDate onDate, UUID excludeRequestId) {
        BigDecimal valid = creditRepository.findByEmployeeUserIdAndStatusOrderByExpiresOnAscCreatedAtAsc(employeeId, CompOffCredit.AVAILABLE)
                .stream().filter(c -> !c.getExpiresOn().isBefore(onDate))
                .map(CompOffCredit::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
        return valid.subtract(pendingLeaveDays(employeeId, excludeRequestId));
    }

    /** Called by LeaveService before saving a Comp-Off leave request (submit and edit). */
    @Transactional(readOnly = true)
    public void validateLeaveRequest(UUID employeeId, LocalDate startDate, BigDecimal totalDays, boolean halfDay, UUID excludeRequestId) {
        CompOffSettings settings = policyService.settings();
        if (!settings.isCompOffEnabled()) throw new IllegalArgumentException("Comp-Off is not enabled by company policy");
        if (halfDay && !settings.isAllowPartialDay()) throw new IllegalArgumentException("Half-day Comp-Off is not allowed by company policy");
        BigDecimal available = availableForRequest(employeeId, startDate, excludeRequestId);
        if (available.compareTo(totalDays) < 0) {
            throw new IllegalArgumentException("Leave request exceeds your available Comp-Off balance of "
                    + available.max(BigDecimal.ZERO).stripTrailingZeros().toPlainString() + " day(s).");
        }
    }

    /** Called by LeaveService when a Comp-Off leave request is approved: this is the only place leave spends credit. */
    @Transactional
    public void consumeForApprovedLeave(LeaveRequest request, UUID actorId) {
        List<CompOffCredit> touched;
        try {
            touched = consume(request.getEmployeeUserId(), request.getTotalDays(), request.getStartDate(), request.getId(), false);
        } catch (IllegalStateException e) {
            throw new IllegalStateException("Employee no longer has sufficient Comp-Off balance to approve this request");
        }
        for (CompOffCredit c : touched) {
            event(request.getEmployeeUserId(), c.getId(), "CREDIT_UTILIZED", null,
                    "Leave " + request.getStartDate() + " to " + request.getEndDate(), actorId);
        }
    }

    private BigDecimal pendingLeaveDays(UUID employeeId, UUID excludeRequestId) {
        Optional<LeaveType> type = leaveTypeRepository.findByCode(LEAVE_TYPE_CODE);
        if (type.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = excludeRequestId == null
                ? leaveRequestRepository.sumTotalDaysByEmployeeUserIdAndLeaveTypeIdInAndStatusAndStartDateBetween(
                        employeeId, List.of(type.get().getId()), "PENDING", FAR_PAST, FAR_FUTURE)
                : leaveRequestRepository.sumTotalDaysByEmployeeUserIdAndLeaveTypeIdInAndStatusAndStartDateBetweenAndIdNot(
                        employeeId, List.of(type.get().getId()), "PENDING", FAR_PAST, FAR_FUTURE, excludeRequestId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /**
     * Spends {@code days} from the employee's valid credits, soonest expiry first. {@code leaveRequestId}
     * non-null records a utilisation (leave); null with {@code deduction} records an HR deduction.
     */
    private List<CompOffCredit> consume(UUID employeeId, BigDecimal days, LocalDate validOn, UUID leaveRequestId, boolean deduction) {
        List<CompOffCredit> credits = creditRepository
                .findByEmployeeUserIdAndStatusOrderByExpiresOnAscCreatedAtAsc(employeeId, CompOffCredit.AVAILABLE).stream()
                .filter(c -> !c.getExpiresOn().isBefore(validOn) && c.remaining().signum() > 0).toList();
        BigDecimal total = credits.stream().map(CompOffCredit::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(days) < 0) {
            throw new IllegalStateException("Insufficient Comp-Off balance: " + total.stripTrailingZeros().toPlainString() + " day(s) available");
        }
        BigDecimal left = days;
        List<CompOffCredit> touched = new ArrayList<>();
        for (CompOffCredit c : credits) {
            if (left.signum() <= 0) break;
            BigDecimal take = c.remaining().min(left);
            if (deduction) c.setDeductedDays(c.getDeductedDays().add(take));
            else c.setUsedDays(c.getUsedDays().add(take));
            creditRepository.save(c);
            if (leaveRequestId != null) {
                utilizationRepository.save(CompOffUtilization.builder().creditId(c.getId()).leaveRequestId(leaveRequestId).days(take).build());
            }
            touched.add(c);
            left = left.subtract(take);
        }
        return touched;
    }

    // ------------------------------------------------------------------ employee view

    @Transactional(readOnly = true)
    public MyCompOff mine(String actorEmail) {
        User actor = actor(actorEmail);
        UUID id = actor.getId();
        List<CompOffCredit> credits = creditRepository.findByEmployeeUserIdOrderByEarnedOnDescCreatedAtDesc(id);
        Map<UUID, CompOffCredit> byClaim = credits.stream().filter(c -> c.getClaimId() != null)
                .collect(Collectors.toMap(CompOffCredit::getClaimId, c -> c, (a, b) -> a));
        List<ClaimDto> claims = claimRepository.findByEmployeeUserIdOrderByWorkDateDesc(id).stream()
                .map(c -> toClaimDto(c, null, byClaim.get(c.getId()))).toList();
        List<EventDto> history = toEventDtos(eventRepository.findByEmployeeUserIdOrderByCreatedAtDesc(id));
        return new MyCompOff(policyService.getPolicy(), balance(id, credits), claims, toCreditDtos(credits), history);
    }

    @Transactional(readOnly = true)
    public BalanceDto balance(UUID employeeId, List<CompOffCredit> credits) {
        LocalDate today = LocalDate.now();
        BigDecimal earned = BigDecimal.ZERO, available = BigDecimal.ZERO, used = BigDecimal.ZERO,
                pending = BigDecimal.ZERO, expired = BigDecimal.ZERO, adjusted = BigDecimal.ZERO;
        for (CompOffCredit c : credits) {
            used = used.add(c.getUsedDays());
            expired = expired.add(c.getExpiredDays());
            adjusted = adjusted.add(c.getDeductedDays());
            if (CompOffCredit.PENDING_APPROVAL.equals(c.getStatus())) pending = pending.add(c.getCreditDays());
            if (CompOffCredit.AVAILABLE.equals(c.getStatus()) || CompOffCredit.EXPIRED.equals(c.getStatus())) {
                earned = earned.add(c.getCreditDays());
            }
            if (CompOffCredit.AVAILABLE.equals(c.getStatus()) && !c.getExpiresOn().isBefore(today)) available = available.add(c.remaining());
        }
        pending = pending.add(pendingLeaveDays(employeeId, null));
        return new BalanceDto(earned, available, used, pending, expired, adjusted);
    }

    @Transactional(readOnly = true)
    public List<EventDto> history(UUID employeeId, String actorEmail) {
        assertCanView(actor(actorEmail), employeeId);
        return toEventDtos(eventRepository.findByEmployeeUserIdOrderByCreatedAtDesc(employeeId));
    }

    @Transactional(readOnly = true)
    public List<ClaimDto> claims(LocalDate from, LocalDate to, String actorEmail) {
        User actor = actor(actorEmail);
        List<CompOffClaim> rows = claimRepository.findByWorkDateBetweenOrderByWorkDateDesc(from, to);
        if (!permissionService.hasAuthority(actor, "leave:VIEW:ALL")) {
            Set<UUID> team = new HashSet<>(historyRepository.findCurrentDirectReportIds(actor.getId()));
            rows = rows.stream().filter(c -> team.contains(c.getEmployeeUserId())).toList();
        }
        Map<UUID, Employee> emps = employeesById(rows.stream().map(CompOffClaim::getEmployeeUserId).collect(Collectors.toSet()));
        Map<UUID, CompOffCredit> credits = creditRepository.findAllById(
                rows.stream().map(CompOffClaim::getCreditId).filter(java.util.Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(CompOffCredit::getId, c -> c));
        return rows.stream().map(c -> toClaimDto(c, emps.get(c.getEmployeeUserId()), credits.get(c.getCreditId()))).toList();
    }

    /** Team scope unless the caller can view everyone. */
    void assertCanView(User actor, UUID employeeId) {
        if (actor.getId().equals(employeeId) || permissionService.hasAuthority(actor, "leave:VIEW:ALL")) return;
        if (historyRepository.findCurrentDirectReportIds(actor.getId()).contains(employeeId)) return;
        throw new AccessDeniedException("You cannot view this employee's Comp-Off");
    }

    // ------------------------------------------------------------------ mapping helpers

    private CompOffCredit claimCredit(CompOffClaim claim) {
        return claim.getCreditId() == null ? null : creditRepository.findById(claim.getCreditId()).orElse(null);
    }

    private ClaimDto toClaimDto(CompOffClaim c, Employee e, CompOffCredit credit) {
        if (e == null) e = employeeRepository.findById(c.getEmployeeUserId()).orElse(null);
        return new ClaimDto(c.getId(), c.getEmployeeUserId(), e != null ? e.getEmployeeCode() : null,
                e != null ? e.getFullName() : "Unknown", c.getWorkDate(), c.getDayType(), c.getWorkedMinutes(),
                c.getEligibleMinutes(), c.getCreditDays(), c.getChoice(), credit != null ? credit.getStatus() : null,
                credit != null ? credit.getExpiresOn() : null);
    }

    List<CreditDto> toCreditDtos(List<CompOffCredit> credits) {
        Map<UUID, Employee> emps = employeesById(credits.stream().map(CompOffCredit::getEmployeeUserId).collect(Collectors.toSet()));
        return credits.stream().map(c -> {
            Employee e = emps.get(c.getEmployeeUserId());
            return new CreditDto(c.getId(), c.getEmployeeUserId(), e != null ? e.getEmployeeCode() : null,
                    e != null ? e.getFullName() : "Unknown",
                    e != null && e.getDepartment() != null ? e.getDepartment().getName() : null, c.getSource(),
                    c.getWorkDate(), c.getCreditDays(), c.getUsedDays(), c.getDeductedDays(), c.getExpiredDays(),
                    c.remaining(), c.getStatus(), c.getEarnedOn(), c.getExpiresOn(), c.getReason(), c.getReviewComment());
        }).toList();
    }

    private List<EventDto> toEventDtos(List<CompOffEvent> rows) {
        Set<UUID> actorIds = rows.stream().map(CompOffEvent::getActorId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> names = actorIds.isEmpty() ? Map.of() : employeeRepository.findNamesByUserIds(actorIds).stream()
                .collect(Collectors.toMap(r -> (UUID) r[0], r -> (String) r[1]));
        return rows.stream().map(e -> new EventDto(e.getId(), e.getEvent(), e.getDays(), e.getNote(),
                e.getActorId() == null ? "System" : names.getOrDefault(e.getActorId(), "Unknown"), e.getCreatedAt())).toList();
    }

    Map<UUID, Employee> employeesById(Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return employeeRepository.findAllByIdWithDepartment(ids).stream().collect(Collectors.toMap(Employee::getUserId, e -> e));
    }

    private void event(UUID employeeId, UUID creditId, String event, BigDecimal days, String note, UUID actorId) {
        eventRepository.save(CompOffEvent.builder().employeeUserId(employeeId).creditId(creditId).event(event)
                .days(days).note(note).actorId(actorId).build());
    }

    private String employeeName(UUID userId) {
        return employeeRepository.findById(userId).map(Employee::getFullName).orElse("An employee");
    }

    private static String key(UUID employeeId, LocalDate date) {
        return employeeId + "|" + date;
    }

    private User actor(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("Actor not found"));
    }
}
