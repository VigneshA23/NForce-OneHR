package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.CreateLeaveRequestRequest;
import com.nforce.onehr.dto.LeaveBalanceResponse;
import com.nforce.onehr.dto.LeaveRequestResponse;
import com.nforce.onehr.dto.LeaveTypeResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.AttendancePenaltyStatus;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Holiday;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveDurationType;
import com.nforce.onehr.entity.LeaveHalfDaySession;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.HolidayRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveRequestRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LeaveService {

    // Holders of leave:APPROVE:ALL (seeded: HR_ADMIN, SUPER_ADMIN) may decide any leave request
    // regardless of the literal manager relationship — same override convention as
    // RegularizationService/AssetService/OvertimeRequestService/AttendanceRequestService/
    // WebClockInService (ONEHR-140 follow-up: this service was the one workflow missing it,
    // causing HR Admin "Access Denied").
    private static final String APPROVER_OVERRIDE_AUTHORITY = "leave:APPROVE:ALL";
    // Required of every non-override approver, on top of being the request's reporting manager.
    private static final String APPROVER_TEAM_AUTHORITY = "leave:APPROVE:TEAM";

    // Annual/Sick/Casual are still independently selectable when submitting a request (see
    // #listTypes, untouched), but for calculation/display they share ONE consolidated balance —
    // the ANNUAL LeaveBalance row, quota 15 days/year. Sick and Casual keep their own LeaveType
    // and LeaveBalance rows (never deleted/renamed/migrated) but those balance rows are
    // functionally vestigial: submission and approval always redirect to the ANNUAL row, and the
    // pending-reservation sum spans all three type IDs. See #isAnnualBalanceLeaveType,
    // #annualLeaveType, #annualBalanceGroupTypeIds.
    private static final Set<String> ANNUAL_BALANCE_GROUP_CODES = Set.of("ANNUAL", "SICK", "CASUAL");
    private static final String ANNUAL_LEAVE_TYPE_CODE = "ANNUAL";

    // A REJECTED request never blocks a new overlapping submission — only these two statuses do.
    private static final Set<String> OVERLAP_BLOCKING_STATUSES = Set.of("PENDING", "APPROVED");

    // Matches RegularizationService's NOTIFICATION_DATE_FMT — same "d MMM yyyy" convention used
    // app-wide for dates embedded in notification text.
    private static final DateTimeFormatter NOTIFICATION_DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");
    // Weekday included so a "non-working day" rejection is self-explanatory (e.g. "Saturday, 5 Dec 2026").
    private static final DateTimeFormatter LEAVE_DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMM yyyy", Locale.ENGLISH);
    private static final String APPROVALS_LINK = "/approvals?type=LEAVE";
    private static final String EMPLOYEE_LEAVE_LINK = "/my-requests?type=LEAVE";

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final NotificationService notificationService;
    private final AttendanceProperties attendanceProperties;
    // Phase 4: weekly-off (not Shift.workingDays, now removed) is the sole source of truth for
    // which days a leave request may span — resolved per date via the same batch resolver
    // WorkingDayService/ExceptionService already share, never Employee.weeklyOffPolicy directly.
    private final WorkingDayService workingDayService;
    private final HolidayRepository holidayRepository;
    private final EffectivePermissionService effectivePermissionService;
    private final AttendancePenaltyRepository attendancePenaltyRepository;
    private static final ObjectMapper PENALTY_JSON = new ObjectMapper();
    // @Lazy breaks a genuine construction-time cycle: LeaveService -> ExceptionService ->
    // AttendancePenaltyEvaluationService -> EmployeeService -> LeaveService (EmployeeService has
    // depended on LeaveService since before this change). A lazy proxy defers resolving the real
    // ExceptionService bean until approve() actually calls it, well after the context has finished
    // starting up, rather than needing it live during EmployeeService's own construction.
    @Lazy
    private final ExceptionService exceptionService;

    @Transactional(readOnly = true)
    public List<LeaveTypeResponse> listTypes() {
        return leaveTypeRepository.findAll().stream()
                .map(t -> LeaveTypeResponse.builder().id(t.getId()).code(t.getCode()).name(t.getName())
                        .classification(t.getClassification()).build())
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveBalanceResponse> listMyBalances(String actorEmail) {
        User actor = requireActor(actorEmail);
        int year = LocalDateTime.now().getYear();
        // Sick/Casual balance rows exist (never deleted) but are vestigial once consolidated —
        // only the ANNUAL row surfaces here, so the balance list/pie chart shows ONE Annual Leave
        // entry instead of three.
        return leaveBalanceRepository.findByEmployeeUserIdAndYear(actor.getId(), year).stream()
                .filter(this::isDisplayableBalance)
                .map(this::toBalanceResponse)
                .collect(Collectors.toList());
    }

    /**
     * Current-year leave balances for the caller's current direct reports — backs My Team's
     * per-report "Leave balance" column. Same filtering as {@link #listMyBalances}, with each row
     * tagged by employeeUserId so the frontend can group them per report.
     */
    @Transactional(readOnly = true)
    public List<LeaveBalanceResponse> listTeamBalances(String actorEmail) {
        User actor = requireActor(actorEmail);
        List<UUID> reportIds = historyRepository.findCurrentDirectReportIds(actor.getId());
        if (reportIds.isEmpty()) {
            return List.of();
        }
        int year = LocalDateTime.now().getYear();
        return leaveBalanceRepository.findByEmployeeUserIdInAndYear(reportIds, year).stream()
                .filter(this::isDisplayableBalance)
                .map(b -> {
                    LeaveBalanceResponse r = toBalanceResponse(b);
                    r.setEmployeeUserId(b.getEmployeeUserId());
                    return r;
                })
                .collect(Collectors.toList());
    }

    // Unpaid leave types (see LeaveType#isPaid) never deduct from/track a balance — see
    // #submitRequest and #approve — so they have nothing meaningful to show here.
    private boolean isDisplayableBalance(LeaveBalance b) {
        return b.getLeaveType().isPaid()
                && (!isAnnualBalanceLeaveType(b.getLeaveType())
                        || ANNUAL_LEAVE_TYPE_CODE.equals(b.getLeaveType().getCode()));
    }

    /**
     * Grants each configured LeaveType a default opening balance for the current year, unless
     * the employee already has one (idempotent — safe to call more than once). Mirrors the
     * "20 days per type per employee" default that V19's one-time migration seed used. Called
     * on employee creation so new hires aren't left with zero balances (see UserManagementService
     * and EmployeeService).
     */
    @Transactional
    public void initializeDefaultBalances(UUID employeeUserId) {
        int year = LocalDate.now().getYear();
        Set<UUID> existingTypeIds = leaveBalanceRepository.findByEmployeeUserIdAndYear(employeeUserId, year)
                .stream().map(b -> b.getLeaveType().getId()).collect(Collectors.toSet());
        for (LeaveType type : leaveTypeRepository.findAll()) {
            if (existingTypeIds.contains(type.getId())) continue;
            // Annual is the consolidated group's canonical balance row and carries the real
            // 15-day quota; Sick/Casual still get a row (never deleted) but it's vestigial, so
            // its seed value is left at the original 20 — nothing ever reads it once submission/
            // approval redirect to the Annual row.
            BigDecimal openingBalance = ANNUAL_LEAVE_TYPE_CODE.equals(type.getCode())
                    ? new BigDecimal("15")
                    : new BigDecimal("20");
            leaveBalanceRepository.save(LeaveBalance.builder()
                    .employeeUserId(employeeUserId)
                    .leaveType(type)
                    .year(year)
                    .totalDays(openingBalance)
                    .usedDays(BigDecimal.ZERO)
                    .build());
        }
    }

    @Transactional
    public LeaveRequestResponse submitRequest(CreateLeaveRequestRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeaveType type = leaveTypeRepository.findByCode(req.getLeaveTypeCode())
                .orElseThrow(() -> new IllegalArgumentException("Unknown leave type: " + req.getLeaveTypeCode()));

        if (req.getEndDate().isBefore(req.getStartDate())) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }
        if (req.isHalfDay() && !req.getEndDate().isEqual(req.getStartDate())) {
            throw new IllegalArgumentException("A half-day request must use the same start and end date");
        }
        if (req.isHalfDay() && !(LeaveHalfDaySession.FIRST_HALF.equals(req.getHalfDaySession())
                || LeaveHalfDaySession.SECOND_HALF.equals(req.getHalfDaySession()))) {
            throw new IllegalArgumentException("A half-day request must specify First Half or Second Half");
        }

        // "Today" is resolved in the business timezone (same convention as AttendanceProperties'
        // other consumers), not the JVM default, so a server running in UTC doesn't roll the day
        // over hours early/late relative to the employee's actual calendar day.
        LocalDate today = LocalDate.now(ZoneId.of(attendanceProperties.getZone()));
        if (req.getStartDate().isBefore(today)) {
            throw new IllegalArgumentException("Leave cannot be requested for a date before today");
        }
        // Every requested date must be one of the employee's shift working days and not a Location
        // holiday — so the plain inclusive day count below is exactly the working days requested.
        requireOnlyWorkingDays(actor.getId(), req.getStartDate(), req.getEndDate());
        // Any existing PENDING/APPROVED request whose date range overlaps this new one blocks the
        // submission, regardless of whether the new request starts today, in the future, or spans
        // multiple days; REJECTED never blocks. Overlap test: existing.start <= new.end AND
        // existing.end >= new.start.
        if (leaveRequestRepository.existsByEmployeeUserIdAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                actor.getId(), OVERLAP_BLOCKING_STATUSES, req.getEndDate(), req.getStartDate())) {
            throw new IllegalArgumentException("You already have a pending or approved leave request that overlaps these dates.");
        }

        BigDecimal totalDays = req.isHalfDay()
                ? new BigDecimal("0.5")
                : BigDecimal.valueOf(ChronoUnit.DAYS.between(req.getStartDate(), req.getEndDate()) + 1);

        // An employee with an available paid leave balance must exhaust/use that before falling
        // back to an Unpaid-classified type — this is a business rule (not day-count-based), so it
        // applies to every Unpaid LeaveType, not just a specific hardcoded one. Checked against ALL
        // of the employee's distinct paid balances for the year (mirrors #listMyBalances' dedup of
        // the Annual/Sick/Casual group), not just the balance tied to the selected type.
        if (!type.isPaid() && hasAnyPositivePaidBalance(actor.getId(), req.getStartDate().getYear())) {
            throw new IllegalArgumentException(
                    "You cannot apply for unpaid leave while you have an available paid leave balance.");
        }

        // Unpaid leave types (see LeaveType#isPaid) don't draw from any LeaveBalance — the
        // requested days must never consume the employee's paid leave balance, so there is
        // nothing to look up or validate against here. Mirrors the skip in #approve below.
        if (type.isPaid()) {
            int year = req.getStartDate().getYear();
            // Sick/Casual requests draw from and are validated against the consolidated Annual
            // balance row — the error message below therefore always names the balance actually
            // being checked (Annual), not the literally-selected type, even though the LeaveRequest
            // itself still records the type the employee actually chose.
            LeaveType balanceType = isAnnualBalanceLeaveType(type) ? annualLeaveType() : type;
            LeaveBalance balance = leaveBalanceRepository
                    .findByEmployeeUserIdAndLeaveTypeIdAndYear(actor.getId(), balanceType.getId(), year)
                    .orElseThrow(() -> new IllegalArgumentException("No " + balanceType.getName() + " balance configured for " + year));

            BigDecimal remaining = availableBalance(balance);
            if (remaining.compareTo(totalDays) < 0) {
                throw new IllegalArgumentException("Leave request exceeds your available " + balanceType.getName()
                        + " balance of " + formatDays(remaining) + " days.");
            }
        } else {
            // Unpaid Leave/Loss of Pay must never be a way to skip past an available paid leave
            // balance — the employee is expected to exhaust Annual/Sick/Casual (and any standalone
            // paid type) first. Summed across every distinct, non-vestigial paid balance row
            // (same row set #listMyBalances already surfaces), not just the requested amount, so
            // even a request smaller than the paid balance is still blocked.
            int year = req.getStartDate().getYear();
            BigDecimal availablePaidDays = leaveBalanceRepository.findByEmployeeUserIdAndYear(actor.getId(), year).stream()
                    .filter(b -> b.getLeaveType().isPaid())
                    .filter(b -> !isAnnualBalanceLeaveType(b.getLeaveType())
                            || ANNUAL_LEAVE_TYPE_CODE.equals(b.getLeaveType().getCode()))
                    .map(this::availableBalance)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (availablePaidDays.signum() > 0) {
                throw new IllegalArgumentException("You have " + formatDays(availablePaidDays)
                        + " day(s) of paid leave available — use that before applying for " + type.getName() + ".");
            }
        }

        LeaveRequest request = LeaveRequest.builder()
                .employeeUserId(actor.getId())
                .leaveType(type)
                .startDate(req.getStartDate())
                .endDate(req.getEndDate())
                .halfDay(req.isHalfDay())
                .halfDaySession(req.isHalfDay() ? req.getHalfDaySession() : null)
                .totalDays(totalDays)
                .status("PENDING")
                .employeeReason(req.getReason().trim())
                // is_half_day stays authoritative (unchanged above); duration_type just mirrors it
                // for the FULL_DAY/HALF_DAY cases this form actually submits — HOURLY/QUARTER_DAY
                // have no submission UI yet, so submitRequest never produces them.
                .durationType(req.isHalfDay() ? LeaveDurationType.HALF_DAY : LeaveDurationType.FULL_DAY)
                .build();
        // A Super Admin's own leave must never be self-approvable: fail the submission up front
        // (rather than leave a request nobody may decide) when neither an eligible manager nor
        // another active Super Admin exists to route it to.
        Optional<SuperAdminRouting> routing = superAdminRouting(actor.getId());
        if (routing.isPresent() && routing.get().approverId() == null && otherActiveSuperAdminIds(actor.getId()).isEmpty()) {
            throw new IllegalArgumentException(
                    "No eligible approver is available for your leave request. Contact your administrator.");
        }
        request = leaveRequestRepository.save(request);

        auditService.log(actor.getId(), "LEAVE_REQUEST_SUBMITTED", request.getId());
        notifySubmission(request, type, actor);
        return toRequestResponse(request);
    }

    /**
     * Edit a still-pending leave request. Only the submitting employee may edit, and only while
     * status is PENDING — approved/rejected requests are immutable history (mirrors
     * RegularizationService#update's precedent). Re-runs every validation {@link #submitRequest}
     * enforces (working days, overlap, balance, unpaid-vs-paid rule), excluding this request's own
     * reservation from each check — see {@link #availableBalance(LeaveBalance, UUID)} — so editing
     * a request never counts its own pre-edit reservation against itself. No notification is sent
     * (same as RegularizationService#update); the approver sees the new details next time they open it.
     */
    @Transactional
    public LeaveRequestResponse update(UUID requestId, CreateLeaveRequestRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeaveRequest existing = leaveRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Leave request not found"));
        if (!existing.getEmployeeUserId().equals(actor.getId())) {
            throw new AccessDeniedException("You can only edit your own requests");
        }
        if (!"PENDING".equals(existing.getStatus())) {
            throw new IllegalStateException("Only pending requests can be edited");
        }
        // Captured once, before `existing` is reassigned by save() below — reassignment means
        // existing.getId() itself isn't effectively final and can't be referenced from the lambda
        // further down (the balance-sum stream).
        final UUID existingId = existing.getId();

        LeaveType type = leaveTypeRepository.findByCode(req.getLeaveTypeCode())
                .orElseThrow(() -> new IllegalArgumentException("Unknown leave type: " + req.getLeaveTypeCode()));

        if (req.getEndDate().isBefore(req.getStartDate())) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }
        if (req.isHalfDay() && !req.getEndDate().isEqual(req.getStartDate())) {
            throw new IllegalArgumentException("A half-day request must use the same start and end date");
        }
        if (req.isHalfDay() && !(LeaveHalfDaySession.FIRST_HALF.equals(req.getHalfDaySession())
                || LeaveHalfDaySession.SECOND_HALF.equals(req.getHalfDaySession()))) {
            throw new IllegalArgumentException("A half-day request must specify First Half or Second Half");
        }

        LocalDate today = LocalDate.now(ZoneId.of(attendanceProperties.getZone()));
        if (req.getStartDate().isBefore(today)) {
            throw new IllegalArgumentException("Leave cannot be requested for a date before today");
        }
        requireOnlyWorkingDays(actor.getId(), req.getStartDate(), req.getEndDate());

        if (leaveRequestRepository.existsByEmployeeUserIdAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndIdNot(
                actor.getId(), OVERLAP_BLOCKING_STATUSES, req.getEndDate(), req.getStartDate(), existingId)) {
            throw new IllegalArgumentException("You already have a pending or approved leave request that overlaps these dates.");
        }

        BigDecimal totalDays = req.isHalfDay()
                ? new BigDecimal("0.5")
                : BigDecimal.valueOf(ChronoUnit.DAYS.between(req.getStartDate(), req.getEndDate()) + 1);

        if (!type.isPaid() && hasAnyPositivePaidBalance(actor.getId(), req.getStartDate().getYear(), existingId)) {
            throw new IllegalArgumentException(
                    "You cannot apply for unpaid leave while you have an available paid leave balance.");
        }

        if (type.isPaid()) {
            int year = req.getStartDate().getYear();
            LeaveType balanceType = isAnnualBalanceLeaveType(type) ? annualLeaveType() : type;
            LeaveBalance balance = leaveBalanceRepository
                    .findByEmployeeUserIdAndLeaveTypeIdAndYear(actor.getId(), balanceType.getId(), year)
                    .orElseThrow(() -> new IllegalArgumentException("No " + balanceType.getName() + " balance configured for " + year));

            BigDecimal remaining = availableBalance(balance, existingId);
            if (remaining.compareTo(totalDays) < 0) {
                throw new IllegalArgumentException("Leave request exceeds your available " + balanceType.getName()
                        + " balance of " + formatDays(remaining) + " days.");
            }
        } else {
            int year = req.getStartDate().getYear();
            BigDecimal availablePaidDays = leaveBalanceRepository.findByEmployeeUserIdAndYear(actor.getId(), year).stream()
                    .filter(b -> b.getLeaveType().isPaid())
                    .filter(b -> !isAnnualBalanceLeaveType(b.getLeaveType())
                            || ANNUAL_LEAVE_TYPE_CODE.equals(b.getLeaveType().getCode()))
                    .map(b -> availableBalance(b, existingId))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (availablePaidDays.signum() > 0) {
                throw new IllegalArgumentException("You have " + formatDays(availablePaidDays)
                        + " day(s) of paid leave available — use that before applying for " + type.getName() + ".");
            }
        }

        String before = auditSnapshot.toJson(Map.of(
                "leaveTypeCode", existing.getLeaveType().getCode(),
                "startDate", existing.getStartDate().toString(),
                "endDate", existing.getEndDate().toString(),
                "halfDay", existing.isHalfDay(),
                "totalDays", existing.getTotalDays(),
                "reason", existing.getEmployeeReason()));

        existing.setLeaveType(type);
        existing.setStartDate(req.getStartDate());
        existing.setEndDate(req.getEndDate());
        existing.setHalfDay(req.isHalfDay());
        existing.setHalfDaySession(req.isHalfDay() ? req.getHalfDaySession() : null);
        existing.setTotalDays(totalDays);
        existing.setEmployeeReason(req.getReason().trim());
        existing.setDurationType(req.isHalfDay() ? LeaveDurationType.HALF_DAY : LeaveDurationType.FULL_DAY);
        existing = leaveRequestRepository.save(existing);

        String after = auditSnapshot.toJson(Map.of(
                "leaveTypeCode", existing.getLeaveType().getCode(),
                "startDate", existing.getStartDate().toString(),
                "endDate", existing.getEndDate().toString(),
                "halfDay", existing.isHalfDay(),
                "totalDays", existing.getTotalDays(),
                "reason", existing.getEmployeeReason()));
        auditService.log(actor.getId(), "LEAVE_REQUEST_UPDATED", existing.getId(), before, after);
        return toRequestResponse(existing);
    }

    /**
     * Rejects the request at the first date in [start, end] that is an active holiday of the
     * employee's Location or a weekly-off day under the Weekly Off Policy governing that date.
     * Resolved per date (never once for the whole range) via {@link WorkingDayService}'s shared
     * batch resolver — the same one {@code WorkingDayService}/{@code ExceptionService} already use
     * — since a policy reassignment or policy-definition edit can land inside the requested range.
     * One assignment-history query and one policy-version-history query back the whole range
     * (built once below), not a query per date. No effective Weekly Off Policy assignment at all
     * falls back to Saturday/Sunday — the same explicit, pre-existing no-assignment behaviour this
     * preserves (D1: never a silently-assigned default policy). Shift.workingDays ("Applicable
     * Days") no longer has any bearing here — removed in Phase 4; WeeklyOffPolicy is now the sole
     * source of truth for an employee's working days.
     */
    private void requireOnlyWorkingDays(UUID employeeUserId, LocalDate start, LocalDate end) {
        Map<LocalDate, String> holidayNames = employeeRepository.findById(employeeUserId)
                .map(Employee::getLocation)
                .map(location -> holidayRepository.findByLocation_IdAndActiveTrueOrderByHolidayDateAsc(location.getId())
                        .stream().collect(Collectors.toMap(Holiday::getHolidayDate, Holiday::getHolidayName, (a, b) -> a)))
                .orElse(Map.of());
        WorkingDayService.WeeklyOffBatchResolver offDaysResolver =
                workingDayService.weeklyOffDaysResolver(List.of(employeeUserId));
        for (LocalDate date : start.datesUntil(end.plusDays(1)).toList()) {
            String holiday = holidayNames.get(date);
            if (holiday != null) {
                throw new IllegalArgumentException("Leave cannot be applied for " + date.format(LEAVE_DATE_FMT)
                        + " — it is a holiday (" + holiday + "). Please choose only your working days.");
            }
            if (offDaysResolver.offDaysOn(employeeUserId, date).contains(date.getDayOfWeek())) {
                throw new IllegalArgumentException("Leave cannot be applied for " + date.format(LEAVE_DATE_FMT)
                        + " — it is a weekly-off day under your Weekly Off Policy. Please choose only your working days.");
            }
        }
    }

    @Transactional(readOnly = true)
    public List<LeaveRequestResponse> listMyRequests(String actorEmail) {
        User actor = requireActor(actorEmail);
        List<LeaveRequest> requests = leaveRequestRepository.findByEmployeeUserIdOrderByCreatedAtDesc(actor.getId());
        Map<UUID, String> namesById = namesByUserIds(collectNameIds(requests));
        Map<UUID, String> codesById = codesByUserIds(Set.of(actor.getId()));
        List<LeaveRequestResponse> rows = requests.stream()
                .map(r -> toRequestResponse(r, namesById, codesById))
                .collect(Collectors.toList());
        rows.addAll(penaltyDeductionRows(actor.getId(), employeeName(actor.getId()), codesById.get(actor.getId())));
        rows.sort(Comparator.comparing(LeaveRequestResponse::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
    }

    /**
     * ONEHR-645: a PAID_LEAVE attendance penalty debits {@code LeaveBalance.usedDays} directly
     * (PenaltyDeductionService) without any LeaveRequest, so the deduction was invisible in Leave
     * history, My Requests and My Attendance (whose Leave day-tag reads this same list). Surfaces
     * each still-active penalty as a read-only, already-APPROVED row per debited leave type —
     * synthesized, never persisted, so the balance isn't debited twice and no leave-approval side
     * effects (penalty re-evaluation/reversal) fire. A cancelled/reversed penalty drops out
     * automatically, matching AttendancePenaltyService#restoreLeaveBalance crediting it back.
     */
    private List<LeaveRequestResponse> penaltyDeductionRows(UUID employeeUserId, String employeeName, String employeeCode) {
        List<LeaveRequestResponse> rows = new ArrayList<>();
        for (AttendancePenalty p : attendancePenaltyRepository.findByEmployeeUserIdAndStatusAndLeaveDeductionDaysGreaterThan(
                employeeUserId, AttendancePenaltyStatus.PENDING_REVIEW, BigDecimal.ZERO)) {
            Map<String, BigDecimal> breakdown;
            try {
                breakdown = p.getLeaveBreakdown() == null ? Map.of()
                        : PENALTY_JSON.readValue(p.getLeaveBreakdown(), new TypeReference<Map<String, BigDecimal>>() {});
            } catch (JsonProcessingException e) {
                continue;
            }
            String reason = "Attendance penalty: " + AttendancePenaltyEvaluationService.humanizeDiscrepancyType(p.getDiscrepancyType());
            breakdown.forEach((code, days) -> leaveTypeRepository.findByCode(code).ifPresent(type -> rows.add(
                    LeaveRequestResponse.builder()
                            // Stable and unique per (penalty, leave type) — a cascade across types yields several rows.
                            .id(UUID.nameUUIDFromBytes((p.getId() + ":" + code).getBytes()))
                            .employeeUserId(employeeUserId)
                            .employeeName(employeeName)
                            .employeeCode(employeeCode)
                            .leaveTypeCode(type.getCode())
                            .leaveTypeName(type.getName())
                            .leaveTypeClassification(type.getClassification())
                            .startDate(p.getIncidentDate())
                            .endDate(p.getIncidentDate())
                            .totalDays(days)
                            .status("APPROVED")
                            .employeeReason(reason)
                            .decisionReason("Deducted automatically by attendance penalty")
                            .decidedByName("System")
                            .decidedAt(p.getCreatedAt())
                            .createdAt(p.getCreatedAt())
                            .build())));
        }
        return rows;
    }

    /**
     * Reporting manager sees only own reports' pending requests; HR Admin/Super Admin see all —
     * EXCEPT a Super Admin's own leave request, which is deliberately excluded from this blanket
     * override view unless the caller is that request's routed approver (see
     * {@link #superAdminRouting}). Without that exclusion, every HR Admin and every other
     * Super Admin in the org would see (and, per {@link #requireAuthorizedApprover}, still not be
     * able to decide on) a Super Admin's leave purely by holding an override role — this is the
     * "HR_ADMIN must never receive a SUPER_ADMIN's Leave Request merely because the requester has
     * the SUPER_ADMIN role" rule. A manager who is NOT also HR Admin/Super Admin still sees a
     * Super Admin direct report's request via the branch below exactly as before — that branch is
     * already scoped to actual direct reports, which is precisely who this routing routes to.
     * Anyone else needs leave:APPROVE:TEAM to see a queue at all (a reporting line alone grants
     * nothing), and nobody's queue ever contains their own request.
     */
    @Transactional(readOnly = true)
    public List<LeaveRequestResponse> listPendingApprovals(String actorEmail) {
        User actor = requireActor(actorEmail);
        if (!hasOverrideRole(actor) && !effectivePermissionService.hasAuthority(actor, APPROVER_TEAM_AUTHORITY)) {
            throw new AccessDeniedException("Missing " + APPROVER_TEAM_AUTHORITY);
        }
        if (hasOverrideRole(actor)) {
            List<LeaveRequest> allPending = leaveRequestRepository.findByStatusOrderByCreatedAtAsc("PENDING");
            // Batched equivalent of superAdminRouting — which does
            // 2-3 DB round trips per employee, which turned this into an N+1 query storm against a
            // remote DB (took 40+ seconds with a modest pending-request count). Requesters who
            // hold SUPER_ADMIN are normally 0-1 out of the whole pending set, so resolving that
            // routing decision per unique requester once, up front, is equivalent but O(1) queries
            // for everyone else instead of O(N).
            Map<UUID, SuperAdminRouting> routingById = superAdminRoutingByEmployeeId(
                    allPending.stream().map(LeaveRequest::getEmployeeUserId).collect(Collectors.toSet()));
            boolean actorIsSuperAdmin = employeeHasRole(actor, "SUPER_ADMIN");
            List<LeaveRequest> requests = allPending.stream()
                    .filter(r -> !r.getEmployeeUserId().equals(actor.getId()))
                    .filter(r -> {
                        SuperAdminRouting routing = routingById.get(r.getEmployeeUserId());
                        if (routing == null) return true;
                        return routing.approverId() != null
                                ? routing.approverId().equals(actor.getId())
                                : actorIsSuperAdmin;
                    })
                    .collect(Collectors.toList());
            Map<UUID, String> namesById = namesByUserIds(collectNameIds(requests));
            Map<UUID, String> codesById = codesByUserIds(collectEmployeeIds(requests));
            return requests.stream()
                    .map(r -> toRequestResponse(r, namesById, codesById))
                    .collect(Collectors.toList());
        }
        List<UUID> reportIds = historyRepository.findByManagerUserIdAndEffectiveToIsNull(actor.getId()).stream()
                .map(EmployeeManagerHistory::getEmployeeUserId)
                .collect(Collectors.toList());
        if (reportIds.isEmpty()) {
            return List.of();
        }
        List<LeaveRequest> requests = leaveRequestRepository.findByEmployeeUserIdInAndStatusOrderByCreatedAtAsc(reportIds, "PENDING");
        Map<UUID, String> namesById = namesByUserIds(collectNameIds(requests));
        Map<UUID, String> codesById = codesByUserIds(collectEmployeeIds(requests));
        return requests.stream()
                .map(r -> toRequestResponse(r, namesById, codesById))
                .collect(Collectors.toList());
    }

    /**
     * Approved leave for the caller's current direct reports overlapping [from, to] — backs
     * My Team's "who's on leave today," "out this week," and calendar leave-coloring.
     */
    @Transactional(readOnly = true)
    public List<LeaveRequestResponse> listTeamLeave(String actorEmail, LocalDate from, LocalDate to) {
        User actor = requireActor(actorEmail);
        List<UUID> reportIds = historyRepository.findByManagerUserIdAndEffectiveToIsNull(actor.getId()).stream()
                .map(EmployeeManagerHistory::getEmployeeUserId)
                .collect(Collectors.toList());
        if (reportIds.isEmpty()) {
            return List.of();
        }
        List<LeaveRequest> requests = leaveRequestRepository
                .findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(reportIds, "APPROVED", to, from);
        Map<UUID, String> namesById = namesByUserIds(collectNameIds(requests));
        Map<UUID, String> codesById = codesByUserIds(collectEmployeeIds(requests));
        return requests.stream()
                .map(r -> toRequestResponse(r, namesById, codesById))
                .collect(Collectors.toList());
    }

    /**
     * Approved leave overlapping [from, to], organization-wide — HR's "On Leave" KPI, the
     * org-scoped equivalent of {@link #listTeamLeave} (which is confined to the caller's direct
     * reports). Access is restricted to HR Admin/Super Admin at the controller.
     */
    @Transactional(readOnly = true)
    public List<LeaveRequestResponse> listOrgLeave(LocalDate from, LocalDate to) {
        List<LeaveRequest> requests = leaveRequestRepository
                .findByStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual("APPROVED", to, from);
        Map<UUID, String> namesById = namesByUserIds(collectNameIds(requests));
        Map<UUID, String> codesById = codesByUserIds(collectEmployeeIds(requests));
        return requests.stream()
                .map(r -> toRequestResponse(r, namesById, codesById))
                .collect(Collectors.toList());
    }

    /**
     * Approved leave for the caller's "Project Team" — every employee (including the caller
     * themselves) who currently reports to the same manager — overlapping [from, to]. Backs the
     * Peers view's "who's on leave today" panel and calendar (ONEHR-73). Mirrors
     * {@link #listTeamLeave} exactly, swapping direct-report resolution for same-manager
     * resolution. Empty if the caller has no manager assigned at all — there's no "team" to
     * belong to in that case.
     */
    @Transactional(readOnly = true)
    public List<LeaveRequestResponse> listPeerLeave(String actorEmail, LocalDate from, LocalDate to) {
        User actor = requireActor(actorEmail);
        if (historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(actor.getId()).isEmpty()) {
            return List.of();
        }
        // findCurrentPeerIds already includes the caller themself (see its own doc comment) — no
        // need to add actor.getId() again here.
        List<UUID> teamIds = historyRepository.findCurrentPeerIds(actor.getId());

        List<LeaveRequest> requests = leaveRequestRepository
                .findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(teamIds, "APPROVED", to, from);
        Map<UUID, String> namesById = namesByUserIds(collectNameIds(requests));
        Map<UUID, String> codesById = codesByUserIds(collectEmployeeIds(requests));
        return requests.stream()
                .map(r -> toRequestResponse(r, namesById, codesById))
                .collect(Collectors.toList());
    }

    @Transactional
    public LeaveRequestResponse approve(UUID requestId, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeaveRequest request = leaveRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Leave request not found"));
        requireAuthorizedApprover(actor, request.getEmployeeUserId());
        if (!"PENDING".equals(request.getStatus())) {
            throw new IllegalStateException("Leave request has already been decided");
        }

        // Unpaid leave types (see LeaveType#isPaid) never deduct from a LeaveBalance — approving
        // one leaves the employee's paid leave balance untouched, per the current classification
        // at approval time (a type changed Paid<->Unpaid after submission is honored as of NOW,
        // not as of when the request was originally submitted — there is no snapshot to fall back
        // to, see LeaveRequest#getLeaveType()).
        if (request.getLeaveType().isPaid()) {
            int year = request.getStartDate().getYear();
            // Approving a Sick/Casual request consumes from the SAME Annual balance row it was
            // validated/reserved against at submission — usedDays on that one row naturally becomes
            // the combined Annual+Sick+Casual approved total, with no separate cross-type sum needed.
            LeaveType balanceType = isAnnualBalanceLeaveType(request.getLeaveType())
                    ? annualLeaveType() : request.getLeaveType();
            LeaveBalance balance = leaveBalanceRepository
                    .findByEmployeeUserIdAndLeaveTypeIdAndYear(request.getEmployeeUserId(), balanceType.getId(), year)
                    .orElseThrow(() -> new IllegalStateException("No leave balance configured for " + year));
            BigDecimal remaining = balance.getTotalDays().subtract(balance.getUsedDays());
            if (remaining.compareTo(request.getTotalDays()) < 0) {
                throw new IllegalStateException("Employee no longer has sufficient balance to approve this request");
            }
            balance.setUsedDays(balance.getUsedDays().add(request.getTotalDays()));
            leaveBalanceRepository.save(balance);
        }

        String before = auditSnapshot.toJson(Map.of("status", "PENDING"));
        request.setStatus("APPROVED");
        request.setDecidedBy(actor.getId());
        request.setDecidedAt(LocalDateTime.now(ZoneId.of(attendanceProperties.getZone())));
        request = leaveRequestRepository.save(request);

        String after = auditSnapshot.toJson(Map.of("status", "APPROVED", "decidedBy", actor.getId().toString()));
        auditService.log(actor.getId(), "LEAVE_REQUEST_APPROVED", request.getId(), before, after);

        notifyDecision(request, "LEAVE_APPROVED", "Leave Request Approved", "approved", null, actor);
        reevaluatePenaltiesForApprovedLeave(request, actor);
        return toRequestResponse(request);
    }

    /**
     * Gap-034: the leave-approval half of the shared re-evaluation engine {@link RegularizationService#approve}
     * already uses — this request's now-approved days may have lowered expected work minutes (or,
     * for a full day, removed the working-day expectation entirely) below what a WORK_HOURS_SHORTAGE
     * or NO_ATTENDANCE penalty on that date assumed when it was applied. Runs once per covered
     * calendar day — a multi-day request can invalidate a different subset of days than others —
     * and reverses only the two discrepancy types leave approval can actually affect
     * ({@link ExceptionService#LEAVE_REEVALUATION_TYPES}), never LATE_ARRIVAL/MISSING_PUNCH, which
     * a leave approval has no bearing on. Covers both full-day and half-day requests identically:
     * the engine re-derives "is this still a shortage" from current data, not from this method
     * knowing which kind of leave was approved.
     */
    private void reevaluatePenaltiesForApprovedLeave(LeaveRequest request, User actor) {
        for (LocalDate date = request.getStartDate(); !date.isAfter(request.getEndDate()); date = date.plusDays(1)) {
            exceptionService.reevaluateAndReverseIfInvalid(request.getEmployeeUserId(), date,
                    ExceptionService.LEAVE_REEVALUATION_TYPES, actor.getId(),
                    "Leave approved for " + date.format(NOTIFICATION_DATE_FMT), "ATTENDANCE_PENALTY_REVERSED");
        }
    }

    @Transactional
    public LeaveRequestResponse reject(UUID requestId, String reason, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeaveRequest request = leaveRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Leave request not found"));
        requireAuthorizedApprover(actor, request.getEmployeeUserId());
        if (!"PENDING".equals(request.getStatus())) {
            throw new IllegalStateException("Leave request has already been decided");
        }

        String before = auditSnapshot.toJson(Map.of("status", "PENDING"));
        request.setStatus("REJECTED");
        request.setDecisionReason(reason.trim());
        request.setDecidedBy(actor.getId());
        request.setDecidedAt(LocalDateTime.now(ZoneId.of(attendanceProperties.getZone())));
        request = leaveRequestRepository.save(request);

        String after = auditSnapshot.toJson(Map.of("status", "REJECTED", "decisionReason", request.getDecisionReason()));
        auditService.log(actor.getId(), "LEAVE_REQUEST_REJECTED", request.getId(), before, after);

        notifyDecision(request, "LEAVE_REJECTED", "Leave Request Rejected", "rejected", request.getDecisionReason(), actor);
        return toRequestResponse(request);
    }

    /**
     * Notifies whoever this request actually routes to — mirrors RegularizationService's and
     * AssetService's submission notifications, which deliberately notify just the resolved
     * approver rather than broadcasting to every HR Admin/Super Admin: those roles already have
     * blanket queue visibility (see {@link #listPendingApprovals}), so a per-request notification
     * to every admin in the org would be exactly the "blindly notify every user" pattern the
     * project avoids elsewhere.
     * <p>
     * For a Super Admin's own request, routing is fully overridden by
     * {@link #superAdminRouting} — their eligible reporting manager if they have one, else every
     * OTHER active Super Admin (never the requester). Everyone else keeps the pre-existing behavior: notify
     * the current reporting manager, no-op if there is none. Reuses {@link NotificationService#send}
     * as-is — no new notification model/queue/delivery mechanism.
     */
    private void notifySubmission(LeaveRequest request, LeaveType type, User actor) {
        List<UUID> recipients;
        Optional<SuperAdminRouting> routing = superAdminRouting(actor.getId());
        if (routing.isPresent()) {
            UUID routed = routing.get().approverId();
            recipients = routed != null ? List.of(routed) : otherActiveSuperAdminIds(actor.getId());
        } else {
            recipients = historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(actor.getId())
                    .map(EmployeeManagerHistory::getManagerUserId)
                    .map(List::of)
                    .orElse(List.of());
        }
        String message = employeeName(actor.getId()) + " has submitted a " + type.getName() + " request from "
                + request.getStartDate().format(NOTIFICATION_DATE_FMT) + " to "
                + request.getEndDate().format(NOTIFICATION_DATE_FMT) + " (" + dayLabel(request.getTotalDays()) + ").";
        for (UUID recipient : recipients) {
            notificationService.send(recipient, "LEAVE_REQUEST_SUBMITTED", "New Leave Request", message, APPROVALS_LINK);
        }
    }

    /**
     * Notifies the employee of the decision — same single recipient as the pre-existing behavior
     * this method replaces (only the message content is richer: leave type, formatted dates, and
     * total days are now included alongside the approver/rejecter name and reason). HR Admin/Super
     * Admin are not separately notified here, matching {@link #notifySubmission}'s reasoning —
     * their blanket approval visibility already covers this. Reuses the existing
     * {@code LEAVE_APPROVED}/{@code LEAVE_REJECTED} notification types rather than introducing new
     * ones, per the project's "reuse the equivalent event" convention.
     */
    private void notifyDecision(LeaveRequest request, String type, String title, String verb, String reason, User actor) {
        String employeeOf = request.getLeaveType().getName() + " request from "
                + request.getStartDate().format(NOTIFICATION_DATE_FMT) + " to "
                + request.getEndDate().format(NOTIFICATION_DATE_FMT) + " (" + dayLabel(request.getTotalDays()) + ")";
        String reasonSuffix = reason != null && !reason.isBlank() ? " Reason: " + reason : "";

        notificationService.send(request.getEmployeeUserId(), type, title,
                "Your " + employeeOf + " has been " + verb + " by " + employeeName(actor.getId()) + "." + reasonSuffix,
                EMPLOYEE_LEAVE_LINK);
    }

    private static String dayLabel(BigDecimal days) {
        return formatDays(days) + " day" + (days.compareTo(BigDecimal.ONE) == 0 ? "" : "s");
    }

    /**
     * Approve/reject authorization gate — BOTH conditions must hold, in this order:
     * <ol>
     *   <li>nobody decides their own leave request;</li>
     *   <li>an explicit permission: {@code leave:APPROVE:TEAM} (cascaded, so {@code ALL} satisfies it);</li>
     *   <li>business eligibility for THIS request — the employee's current reporting manager, or an
     *       {@code leave:APPROVE:ALL} holder (org-wide override).</li>
     * </ol>
     * A reporting relationship alone never authorizes a decision. A Super Admin's OWN leave is
     * routed by {@link #superAdminRouting} instead — the override does not apply to it.
     */
    private void requireAuthorizedApprover(User actor, UUID employeeId) {
        if (actor.getId().equals(employeeId)) {
            throw new AccessDeniedException("You cannot decide on your own leave request");
        }
        Optional<SuperAdminRouting> routing = superAdminRouting(employeeId);
        if (routing.isPresent()) {
            UUID routed = routing.get().approverId();
            boolean allowed = routed != null
                    ? routed.equals(actor.getId())
                    : employeeHasRole(actor, "SUPER_ADMIN") && hasOverrideRole(actor);
            if (!allowed) {
                throw new AccessDeniedException("You are not authorized to decide on this leave request");
            }
            return;
        }
        requireCurrentManagerOf(actor, employeeId);
    }

    /**
     * Permission AND eligibility: holders of leave:APPROVE:ALL (HR Admin/Super Admin, or a custom
     * role granted it) may decide on any employee's leave; everyone else needs leave:APPROVE:TEAM
     * (Manager, or any custom role granted it — no built-in role required) AND must be the
     * employee's current reporting manager.
     */
    private void requireCurrentManagerOf(User actor, UUID employeeId) {
        if (hasOverrideRole(actor)) return;
        if (!effectivePermissionService.hasAuthority(actor, APPROVER_TEAM_AUTHORITY)) {
            throw new AccessDeniedException("Missing " + APPROVER_TEAM_AUTHORITY);
        }
        EmployeeManagerHistory current = historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)
                .orElseThrow(() -> new AccessDeniedException("This employee has no assigned manager"));
        if (!current.getManagerUserId().equals(actor.getId())) {
            throw new AccessDeniedException("You are not the current manager of this employee");
        }
    }

    private boolean hasOverrideRole(User actor) {
        return effectivePermissionService.hasAuthority(actor, APPROVER_OVERRIDE_AUTHORITY);
    }

    /**
     * Routing of a Super Admin's OWN leave, shared by the approval queue, the authorization gate
     * and the submission notification so they can never disagree. {@code approverId} is the
     * employee's current reporting manager — but only if that manager actually holds
     * leave:APPROVE:TEAM (a reporting line alone grants no approval right); {@code null} means "any
     * OTHER active Super Admin". It is never the requester, and never an HR Admin ("HR_ADMIN must
     * never receive a SUPER_ADMIN's Leave Request merely because the requester has the SUPER_ADMIN
     * role").
     */
    private record SuperAdminRouting(UUID approverId) {}

    /** Empty unless {@code employeeId} holds SUPER_ADMIN (then the normal manager/override rules apply). */
    private Optional<SuperAdminRouting> superAdminRouting(UUID employeeId) {
        User employee = userRepository.findById(employeeId).orElse(null);
        if (employee == null || !employeeHasRole(employee, "SUPER_ADMIN")) return Optional.empty();
        return Optional.of(new SuperAdminRouting(eligibleRoutedManagerId(employeeId)));
    }

    /** Batched {@link #superAdminRouting}: one findAllById; history/permission lookups only for actual Super Admins. */
    private Map<UUID, SuperAdminRouting> superAdminRoutingByEmployeeId(Set<UUID> employeeIds) {
        if (employeeIds.isEmpty()) return Map.of();
        Map<UUID, SuperAdminRouting> routingById = new HashMap<>();
        for (User u : userRepository.findAllById(employeeIds)) {
            if (employeeHasRole(u, "SUPER_ADMIN")) {
                routingById.put(u.getId(), new SuperAdminRouting(eligibleRoutedManagerId(u.getId())));
            }
        }
        return routingById;
    }

    private UUID eligibleRoutedManagerId(UUID employeeId) {
        return historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)
                .map(EmployeeManagerHistory::getManagerUserId)
                .filter(managerId -> userRepository.findById(managerId)
                        .map(m -> effectivePermissionService.hasAuthority(m, APPROVER_TEAM_AUTHORITY))
                        .orElse(false))
                .orElse(null);
    }

    private List<UUID> otherActiveSuperAdminIds(UUID requesterId) {
        return userRepository.findActiveSuperAdmins().stream()
                .map(User::getId)
                .filter(id -> !id.equals(requesterId))
                .toList();
    }

    // Deliberately the literal role, not a permission: this is routing for a Super Admin's OWN
    // leave (who decides it), not a check on what the actor was granted — see V234's header.
    private boolean employeeHasRole(User user, String roleCode) {
        return user.getRoles().stream().anyMatch(r -> roleCode.equals(r.getCode()));
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private String employeeName(UUID userId) {
        return employeeRepository.findById(userId)
                .map(Employee::getFullName)
                .orElseGet(() -> userRepository.findById(userId).map(User::getEmail).orElse("Unknown"));
    }

    /** Employee code for the single-record {@link #toRequestResponse(LeaveRequest)} path — null
     * for a User with no Employee row (auth-only account), same as the batched variant below. */
    private String employeeCode(UUID userId) {
        return employeeRepository.findById(userId).map(Employee::getEmployeeCode).orElse(null);
    }

    /**
     * Batch counterpart of {@link #employeeName(UUID)} for list-mapping call sites
     * (listMyRequests/listPendingApprovals/listTeamLeave/listOrgLeave/listPeerLeave): resolves
     * every id in one {@link EmployeeRepository#findNamesByUserIds} call instead of one query per
     * row, falling back to a single batch {@code userRepository.findAllById} (email) for any ids
     * Employee doesn't cover — same two-tier fallback as {@link #employeeName(UUID)}, just batched.
     */
    private Map<UUID, String> namesByUserIds(Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<UUID, String> namesById = new HashMap<>();
        for (Object[] row : employeeRepository.findNamesByUserIds(ids)) {
            namesById.put((UUID) row[0], (String) row[1]);
        }
        Set<UUID> missing = new HashSet<>(ids);
        missing.removeAll(namesById.keySet());
        if (!missing.isEmpty()) {
            for (User u : userRepository.findAllById(missing)) {
                namesById.put(u.getId(), u.getEmail());
            }
        }
        return namesById;
    }

    /** Collects the distinct ids {@link #toRequestResponse(LeaveRequest, Map, Map)} needs a name for. */
    private Set<UUID> collectNameIds(Collection<LeaveRequest> requests) {
        Set<UUID> ids = new HashSet<>();
        for (LeaveRequest r : requests) {
            ids.add(r.getEmployeeUserId());
            if (r.getDecidedBy() != null) ids.add(r.getDecidedBy());
        }
        return ids;
    }

    /** Distinct requester ids only — the input {@link #codesByUserIds} needs, narrower than
     * {@link #collectNameIds} since decidedBy has no code to resolve. */
    private Set<UUID> collectEmployeeIds(Collection<LeaveRequest> requests) {
        return requests.stream().map(LeaveRequest::getEmployeeUserId).collect(Collectors.toSet());
    }

    /**
     * Batch counterpart of {@link #employeeCode(UUID)} — only ever needed for the requester
     * (unlike {@link #collectNameIds}, decidedBy has no code to resolve), so this takes the plain
     * employeeUserId set rather than duplicating collectNameIds' broader id collection.
     */
    private Map<UUID, String> codesByUserIds(Set<UUID> employeeIds) {
        if (employeeIds.isEmpty()) return Map.of();
        Map<UUID, String> codesById = new HashMap<>();
        for (Object[] row : employeeRepository.findCodesByUserIds(employeeIds)) {
            codesById.put((UUID) row[0], (String) row[1]);
        }
        return codesById;
    }

    /**
     * Single source of truth for "available" balance, shared by {@link #submitRequest} (what may
     * a new request consume) and {@link #toBalanceResponse} (what the balance API — and the Leave
     * page's pie chart — reports as available). Status-aware: APPROVED is already reflected in
     * {@code usedDays} (mutated at {@link #approve}); PENDING requests are additionally reserved
     * here so a second PENDING submission can't collectively exceed the quota; REJECTED never
     * reaches this calculation (excluded by the {@code status = 'PENDING'} filter below). There is
     * no CANCELLED status or update/edit flow in this codebase to account for.
     * <p>
     * When {@code b} is the consolidated Annual balance row, the PENDING reservation spans all
     * three grouped leave-type IDs (Annual/Sick/Casual) — not just Annual's own requests — since
     * they all draw from this one row (see {@link #annualBalanceGroupTypeIds}).
     */
    private BigDecimal availableBalance(LeaveBalance b) {
        return availableBalance(b, null);
    }

    /**
     * {@code excludeRequestId} variant used by {@link #update}: a request being edited is still
     * PENDING in the DB at validation time, so without exclusion its own (pre-edit) reservation
     * would be double-counted against itself, making the balance look smaller than it really is.
     */
    private BigDecimal availableBalance(LeaveBalance b, UUID excludeRequestId) {
        Collection<UUID> pendingTypeIds = isAnnualBalanceLeaveType(b.getLeaveType())
                ? annualBalanceGroupTypeIds()
                : Set.of(b.getLeaveType().getId());
        BigDecimal pendingReserved = excludeRequestId == null
                ? leaveRequestRepository.sumTotalDaysByEmployeeUserIdAndLeaveTypeIdInAndStatusAndStartDateBetween(
                        b.getEmployeeUserId(), pendingTypeIds, "PENDING",
                        LocalDate.of(b.getYear(), 1, 1), LocalDate.of(b.getYear(), 12, 31))
                : leaveRequestRepository.sumTotalDaysByEmployeeUserIdAndLeaveTypeIdInAndStatusAndStartDateBetweenAndIdNot(
                        b.getEmployeeUserId(), pendingTypeIds, "PENDING",
                        LocalDate.of(b.getYear(), 1, 1), LocalDate.of(b.getYear(), 12, 31), excludeRequestId);
        if (pendingReserved == null) pendingReserved = BigDecimal.ZERO;
        return b.getTotalDays().subtract(b.getUsedDays()).subtract(pendingReserved);
    }

    /**
     * True if the employee has any distinct paid-classification LeaveType balance with a
     * positive available balance for the given year — used to block Unpaid submissions (see
     * #submitRequest). Mirrors #listMyBalances' filtering/dedup (paid types only, Annual/Sick/
     * Casual collapsed to the single Annual row) so this reads the same balances a user actually
     * sees, reusing #availableBalance rather than a duplicate calculation.
     */
    private boolean hasAnyPositivePaidBalance(UUID employeeUserId, int year) {
        return hasAnyPositivePaidBalance(employeeUserId, year, null);
    }

    /** {@code excludeRequestId} variant used by {@link #update} — see {@link #availableBalance(LeaveBalance, UUID)}. */
    private boolean hasAnyPositivePaidBalance(UUID employeeUserId, int year, UUID excludeRequestId) {
        return leaveBalanceRepository.findByEmployeeUserIdAndYear(employeeUserId, year).stream()
                .filter(b -> b.getLeaveType().isPaid())
                .filter(b -> !isAnnualBalanceLeaveType(b.getLeaveType())
                        || ANNUAL_LEAVE_TYPE_CODE.equals(b.getLeaveType().getCode()))
                .anyMatch(b -> availableBalance(b, excludeRequestId).compareTo(BigDecimal.ZERO) > 0);
    }

    private boolean isAnnualBalanceLeaveType(LeaveType type) {
        return ANNUAL_BALANCE_GROUP_CODES.contains(type.getCode());
    }

    private LeaveType annualLeaveType() {
        return leaveTypeRepository.findByCode(ANNUAL_LEAVE_TYPE_CODE)
                .orElseThrow(() -> new IllegalStateException("Annual leave type not configured"));
    }

    private Set<UUID> annualBalanceGroupTypeIds() {
        return leaveTypeRepository.findAll().stream()
                .filter(this::isAnnualBalanceLeaveType)
                .map(LeaveType::getId)
                .collect(Collectors.toSet());
    }

    private static String formatDays(BigDecimal days) {
        return days.stripTrailingZeros().toPlainString();
    }

    private LeaveBalanceResponse toBalanceResponse(LeaveBalance b) {
        return LeaveBalanceResponse.builder()
                .leaveTypeCode(b.getLeaveType().getCode())
                .leaveTypeName(b.getLeaveType().getName())
                .year(b.getYear())
                .totalDays(b.getTotalDays())
                .usedDays(b.getUsedDays())
                .remainingDays(availableBalance(b))
                .build();
    }

    private LeaveRequestResponse toRequestResponse(LeaveRequest r) {
        return LeaveRequestResponse.builder()
                .id(r.getId())
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(employeeName(r.getEmployeeUserId()))
                .employeeCode(employeeCode(r.getEmployeeUserId()))
                .leaveTypeCode(r.getLeaveType().getCode())
                .leaveTypeName(r.getLeaveType().getName())
                .leaveTypeClassification(r.getLeaveType().getClassification())
                .startDate(r.getStartDate())
                .endDate(r.getEndDate())
                .halfDay(r.isHalfDay())
                .halfDaySession(r.getHalfDaySession())
                .totalDays(r.getTotalDays())
                .status(r.getStatus())
                .employeeReason(r.getEmployeeReason())
                .decisionReason(r.getDecisionReason())
                .decidedByName(r.getDecidedBy() != null ? employeeName(r.getDecidedBy()) : null)
                .decidedAt(r.getDecidedAt())
                .createdAt(r.getCreatedAt())
                .build();
    }

    /**
     * List-mapping variant of {@link #toRequestResponse(LeaveRequest)}: looks employeeName/
     * decidedByName up in a pre-built map (from {@link #namesByUserIds}) instead of issuing a
     * query per row. Falls back to "Unknown" for an id absent from the map, matching
     * {@link #employeeName(UUID)}'s own fallback. employeeCode comes from a second pre-built map
     * ({@link #codesByUserIds}) — null (not "Unknown") for a missing id, matching
     * {@link #employeeCode(UUID)}'s own fallback, since it's a secondary display detail rather
     * than the primary identity label.
     */
    private LeaveRequestResponse toRequestResponse(LeaveRequest r, Map<UUID, String> namesById, Map<UUID, String> codesById) {
        return LeaveRequestResponse.builder()
                .id(r.getId())
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(namesById.getOrDefault(r.getEmployeeUserId(), "Unknown"))
                .employeeCode(codesById.get(r.getEmployeeUserId()))
                .leaveTypeCode(r.getLeaveType().getCode())
                .leaveTypeName(r.getLeaveType().getName())
                .leaveTypeClassification(r.getLeaveType().getClassification())
                .startDate(r.getStartDate())
                .endDate(r.getEndDate())
                .halfDay(r.isHalfDay())
                .halfDaySession(r.getHalfDaySession())
                .totalDays(r.getTotalDays())
                .status(r.getStatus())
                .employeeReason(r.getEmployeeReason())
                .decisionReason(r.getDecisionReason())
                .decidedByName(r.getDecidedBy() != null ? namesById.getOrDefault(r.getDecidedBy(), "Unknown") : null)
                .decidedAt(r.getDecidedAt())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
