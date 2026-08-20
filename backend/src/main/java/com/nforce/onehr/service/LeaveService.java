package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.CreateLeaveRequestRequest;
import com.nforce.onehr.dto.LeaveBalanceResponse;
import com.nforce.onehr.dto.LeaveRequestResponse;
import com.nforce.onehr.dto.LeaveTypeResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveRequestRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LeaveService {

    // HR_ADMIN/SUPER_ADMIN may decide any leave request regardless of the literal manager
    // relationship — same override convention as RegularizationService/AssetService/
    // OvertimeRequestService/AttendanceRequestService/WebClockInService (ONEHR-140 follow-up:
    // this service was the one workflow missing it, causing HR Admin "Access Denied").
    private static final Set<String> APPROVER_OVERRIDE_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");

    // Annual/Sick/Casual are still independently selectable when submitting a request (see
    // #listTypes, untouched), but for calculation/display they share ONE consolidated balance —
    // the ANNUAL LeaveBalance row, quota 15 days/year. Sick and Casual keep their own LeaveType
    // and LeaveBalance rows (never deleted/renamed/migrated) but those balance rows are
    // functionally vestigial: submission and approval always redirect to the ANNUAL row, and the
    // pending-reservation sum spans all three type IDs. See #isAnnualBalanceLeaveType,
    // #annualLeaveType, #annualBalanceGroupTypeIds.
    private static final Set<String> ANNUAL_BALANCE_GROUP_CODES = Set.of("ANNUAL", "SICK", "CASUAL");
    private static final String ANNUAL_LEAVE_TYPE_CODE = "ANNUAL";

    // A REJECTED request never blocks a new same-day submission — only these two statuses do.
    private static final Set<String> SAME_DAY_BLOCKING_STATUSES = Set.of("PENDING", "APPROVED");

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

    @Transactional(readOnly = true)
    public List<LeaveTypeResponse> listTypes() {
        return leaveTypeRepository.findAll().stream()
                .map(t -> LeaveTypeResponse.builder().id(t.getId()).code(t.getCode()).name(t.getName()).build())
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
                .filter(b -> !isAnnualBalanceLeaveType(b.getLeaveType())
                        || ANNUAL_LEAVE_TYPE_CODE.equals(b.getLeaveType().getCode()))
                .map(this::toBalanceResponse)
                .collect(Collectors.toList());
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

        // "Today" is resolved in the business timezone (same convention as AttendanceProperties'
        // other consumers), not the JVM default, so a server running in UTC doesn't roll the day
        // over hours early/late relative to the employee's actual calendar day.
        LocalDate today = LocalDate.now(ZoneId.of(attendanceProperties.getZone()));
        if (req.getStartDate().isBefore(today)) {
            throw new IllegalArgumentException("Leave cannot be requested for a date before today");
        }
        // Past dates are already rejected above, so a request can only ever "cover" today when it
        // starts today. PENDING/APPROVED block a second same-day request; REJECTED does not.
        if (req.getStartDate().isEqual(today) && leaveRequestRepository
                .existsByEmployeeUserIdAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        actor.getId(), SAME_DAY_BLOCKING_STATUSES, today, today)) {
            throw new IllegalArgumentException("You already have a pending or approved leave request for today");
        }

        BigDecimal totalDays = req.isHalfDay()
                ? new BigDecimal("0.5")
                : BigDecimal.valueOf(ChronoUnit.DAYS.between(req.getStartDate(), req.getEndDate()) + 1);

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

        LeaveRequest request = LeaveRequest.builder()
                .employeeUserId(actor.getId())
                .leaveType(type)
                .startDate(req.getStartDate())
                .endDate(req.getEndDate())
                .halfDay(req.isHalfDay())
                .totalDays(totalDays)
                .status("PENDING")
                .employeeReason(req.getReason().trim())
                .build();
        request = leaveRequestRepository.save(request);

        auditService.log(actor.getId(), "LEAVE_REQUEST_SUBMITTED", request.getId());
        return toRequestResponse(request);
    }

    @Transactional(readOnly = true)
    public List<LeaveRequestResponse> listMyRequests(String actorEmail) {
        User actor = requireActor(actorEmail);
        return leaveRequestRepository.findByEmployeeUserIdOrderByCreatedAtDesc(actor.getId()).stream()
                .map(this::toRequestResponse)
                .collect(Collectors.toList());
    }

    /** Reporting manager sees only own reports' pending requests; HR Admin/Super Admin see all. */
    @Transactional(readOnly = true)
    public List<LeaveRequestResponse> listPendingApprovals(String actorEmail) {
        User actor = requireActor(actorEmail);
        if (hasOverrideRole(actor)) {
            return leaveRequestRepository.findByStatusOrderByCreatedAtAsc("PENDING").stream()
                    .map(this::toRequestResponse)
                    .collect(Collectors.toList());
        }
        List<UUID> reportIds = historyRepository.findByManagerUserIdAndEffectiveToIsNull(actor.getId()).stream()
                .map(EmployeeManagerHistory::getEmployeeUserId)
                .collect(Collectors.toList());
        if (reportIds.isEmpty()) {
            return List.of();
        }
        return leaveRequestRepository.findByEmployeeUserIdInAndStatusOrderByCreatedAtAsc(reportIds, "PENDING").stream()
                .map(this::toRequestResponse)
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
        return leaveRequestRepository
                .findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(reportIds, "APPROVED", to, from)
                .stream()
                .map(this::toRequestResponse)
                .collect(Collectors.toList());
    }

    /**
     * Approved leave overlapping [from, to], organization-wide — HR's "On Leave" KPI, the
     * org-scoped equivalent of {@link #listTeamLeave} (which is confined to the caller's direct
     * reports). Access is restricted to HR Admin/Super Admin at the controller.
     */
    @Transactional(readOnly = true)
    public List<LeaveRequestResponse> listOrgLeave(LocalDate from, LocalDate to) {
        return leaveRequestRepository
                .findByStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual("APPROVED", to, from)
                .stream()
                .map(this::toRequestResponse)
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

        return leaveRequestRepository
                .findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(teamIds, "APPROVED", to, from)
                .stream()
                .map(this::toRequestResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public LeaveRequestResponse approve(UUID requestId, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeaveRequest request = leaveRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Leave request not found"));
        requireCurrentManagerOf(actor, request.getEmployeeUserId());
        if (!"PENDING".equals(request.getStatus())) {
            throw new IllegalStateException("Leave request has already been decided");
        }

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

        String before = auditSnapshot.toJson(Map.of("status", "PENDING"));
        request.setStatus("APPROVED");
        request.setDecidedBy(actor.getId());
        request.setDecidedAt(LocalDateTime.now(ZoneId.of(attendanceProperties.getZone())));
        request = leaveRequestRepository.save(request);

        String after = auditSnapshot.toJson(Map.of("status", "APPROVED", "decidedBy", actor.getId().toString()));
        auditService.log(actor.getId(), "LEAVE_REQUEST_APPROVED", request.getId(), before, after);

        notificationService.send(request.getEmployeeUserId(), "LEAVE_APPROVED",
                "Leave Request Approved",
                "Your leave request from " + request.getStartDate() + " to " + request.getEndDate()
                        + " has been approved by " + employeeName(actor.getId()) + ".",
                "/requests?type=LEAVE");
        return toRequestResponse(request);
    }

    @Transactional
    public LeaveRequestResponse reject(UUID requestId, String reason, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeaveRequest request = leaveRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Leave request not found"));
        requireCurrentManagerOf(actor, request.getEmployeeUserId());
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

        notificationService.send(request.getEmployeeUserId(), "LEAVE_REJECTED",
                "Leave Request Rejected",
                "Your leave request from " + request.getStartDate() + " to " + request.getEndDate()
                        + " has been rejected by " + employeeName(actor.getId()) + ". Reason: " + request.getDecisionReason(),
                "/requests?type=LEAVE");
        return toRequestResponse(request);
    }

    /**
     * Backend enforcement gate for approve/reject: HR Admin/Super Admin may decide on any
     * employee's leave regardless of reporting line; everyone else must be the employee's
     * current reporting manager. Employee-level (and any other non-manager, non-override) users
     * fail both checks and are denied.
     */
    private void requireCurrentManagerOf(User actor, UUID employeeId) {
        if (hasOverrideRole(actor)) return;
        EmployeeManagerHistory current = historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)
                .orElseThrow(() -> new AccessDeniedException("This employee has no assigned manager"));
        if (!current.getManagerUserId().equals(actor.getId())) {
            throw new AccessDeniedException("You are not the current manager of this employee");
        }
    }

    private boolean hasOverrideRole(User actor) {
        return actor.getRoles().stream().anyMatch(r -> APPROVER_OVERRIDE_ROLES.contains(r.getCode()));
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
        Collection<UUID> pendingTypeIds = isAnnualBalanceLeaveType(b.getLeaveType())
                ? annualBalanceGroupTypeIds()
                : Set.of(b.getLeaveType().getId());
        BigDecimal pendingReserved = leaveRequestRepository.sumTotalDaysByEmployeeUserIdAndLeaveTypeIdInAndStatusAndStartDateBetween(
                b.getEmployeeUserId(), pendingTypeIds, "PENDING",
                LocalDate.of(b.getYear(), 1, 1), LocalDate.of(b.getYear(), 12, 31));
        if (pendingReserved == null) pendingReserved = BigDecimal.ZERO;
        return b.getTotalDays().subtract(b.getUsedDays()).subtract(pendingReserved);
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
                .leaveTypeCode(r.getLeaveType().getCode())
                .leaveTypeName(r.getLeaveType().getName())
                .startDate(r.getStartDate())
                .endDate(r.getEndDate())
                .halfDay(r.isHalfDay())
                .totalDays(r.getTotalDays())
                .status(r.getStatus())
                .employeeReason(r.getEmployeeReason())
                .decisionReason(r.getDecisionReason())
                .decidedByName(r.getDecidedBy() != null ? employeeName(r.getDecidedBy()) : null)
                .decidedAt(r.getDecidedAt())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
