package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.expense.*;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExpenseService {

    private static final Set<String> FINAL_APPROVER_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");

    private final ExpenseClaimRepository claimRepo;
    private final ExpenseCategoryRepository categoryRepo;
    private final EmployeeManagerHistoryRepository historyRepo;
    private final UserRepository userRepo;
    private final EmployeeRepository employeeRepo;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final NotificationService notificationService;
    private final AttendanceProperties attendanceProperties;
    private final ApprovalRuleEvaluationService approvalRuleEvaluationService;

    // ── Categories ────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ExpenseCategoryResponse> listCategories() {
        return categoryRepo.findAll().stream().map(this::toCategoryResponse).collect(Collectors.toList());
    }

    @Transactional
    public ExpenseCategoryResponse createCategory(CreateExpenseCategoryRequest req, String actorEmail) {
        requireFinalApprover(actorEmail);
        ExpenseCategory cat = ExpenseCategory.builder()
                .name(req.getName().trim())
                .requiresReceiptAbove(req.getRequiresReceiptAbove())
                .dailyLimit(req.getDailyLimit())
                .secondApprovalAbove(req.getSecondApprovalAbove())
                .build();
        cat = categoryRepo.save(cat);
        return toCategoryResponse(cat);
    }

    @Transactional
    public ExpenseCategoryResponse updateCategory(Integer categoryId, CreateExpenseCategoryRequest req, String actorEmail) {
        requireFinalApprover(actorEmail);
        ExpenseCategory cat = categoryRepo.findById(categoryId)
                .orElseThrow(() -> new IllegalArgumentException("Category not found"));
        cat.setName(req.getName().trim());
        cat.setRequiresReceiptAbove(req.getRequiresReceiptAbove());
        cat.setDailyLimit(req.getDailyLimit());
        cat.setSecondApprovalAbove(req.getSecondApprovalAbove());
        cat = categoryRepo.save(cat);
        return toCategoryResponse(cat);
    }

    // ── Submit claim (all authenticated users) ───────────

    @Transactional
    public ExpenseClaimResponse submit(SubmitExpenseClaimRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        ExpenseCategory category = categoryRepo.findById(req.getCategoryId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown expense category"));
        LocalDate today = (attendanceProperties != null && attendanceProperties.getZone() != null)
                ? LocalDate.now(ZoneId.of(attendanceProperties.getZone()))
                : LocalDate.now();
        if (req.getExpenseDate() != null && req.getExpenseDate().isAfter(today)) {
            throw new IllegalArgumentException("Expense date cannot be in the future");
        }

        // Server-side receipt requirement check — mirrors the frontend's live validation
        boolean receiptRequired = req.getAmount().compareTo(category.getRequiresReceiptAbove()) > 0;
        if (receiptRequired && (req.getReceiptUrl() == null || req.getReceiptUrl().isBlank())) {
            throw new IllegalArgumentException(
                    "A receipt is required for " + category.getName()
                    + " claims above " + category.getRequiresReceiptAbove());
        }

        // Workflow Studio: snapshot the routing decision NOW, at submission, from whichever rule
        // is active for EXPENSE at this exact moment — see ExpenseClaim.requiresSecondApproval's
        // own Javadoc for why this is never re-evaluated later at managerApprove() time.
        ApprovalRuleEvaluationService.Decision decision = approvalRuleEvaluationService.evaluateExpense(req.getAmount());

        ExpenseClaim claim = ExpenseClaim.builder()
                .employeeUserId(actor.getId())
                .categoryId(req.getCategoryId())
                .amount(req.getAmount())
                .expenseDate(req.getExpenseDate())
                .businessPurpose(req.getBusinessPurpose().trim())
                .receiptUrl(req.getReceiptUrl())
                .status("SUBMITTED")
                .requiresSecondApproval(decision.secondApprovalRequired())
                .evaluatedRuleId(decision.evaluatedRuleId())
                .build();
        claim = claimRepo.save(claim);
        auditService.log(actor.getId(), "EXPENSE_SUBMITTED", actor.getId());
        // Notify manager
        String submitterName = employeeName(actor.getId());
        String catName = category.getName();
        String amtStr = String.format("₹%.2f", claim.getAmount());
        historyRepo.findByEmployeeUserIdAndEffectiveToIsNull(actor.getId())
                .ifPresent(h -> notificationService.send(h.getManagerUserId(), "EXPENSE_SUBMITTED",
                        "New Expense Claim",
                        submitterName + " submitted a " + catName + " claim for " + amtStr,
                        "/approvals?type=EXPENSE"));
        return toClaimResponse(claim, category.getName());
    }

    @Transactional(readOnly = true)
    public List<ExpenseClaimResponse> myClaims(String actorEmail) {
        UUID actorId = requireActor(actorEmail).getId();
        return claimRepo.findByEmployeeUserIdOrderByCreatedAtDesc(actorId).stream()
                .map(c -> toClaimResponse(c, categoryName(c.getCategoryId())))
                .collect(Collectors.toList());
    }

    // ── Receipt (secure, on-demand — reuses the existing receiptUrl storage) ──

    /** Decoded receipt bytes ready to stream back, plus enough to set Content-Type/filename. */
    public record ReceiptFile(byte[] data, String contentType, String fileName) {}

    /**
     * Authorization mirrors managerApprove/managerReject exactly (see requireCurrentManagerOf):
     * the claim's own employee, their current manager, or HR_ADMIN/SUPER_ADMIN (blanket
     * fallback authority, same convention as every other approval workflow in this app). This is
     * enforced here — server-side, regardless of what the frontend shows or hides — not just by
     * which pages happen to render a "View Receipt" button.
     *
     * <p>receiptUrl has always been stored as a base64 data: URI (see ExpenseClaim.receiptUrl's
     * own Javadoc) — this decodes that existing storage into real bytes with a correct
     * Content-Type, rather than introducing a second storage mechanism.
     */
    @Transactional(readOnly = true)
    public ReceiptFile getReceipt(UUID claimId, String actorEmail) {
        User actor = requireActor(actorEmail);
        ExpenseClaim claim = claimRepo.findById(claimId)
                .orElseThrow(() -> new NoSuchElementException("Expense claim not found: " + claimId));
        if (!claim.getEmployeeUserId().equals(actor.getId())) {
            requireCurrentManagerOf(actor, claim.getEmployeeUserId());
        }
        if (claim.getReceiptUrl() == null || claim.getReceiptUrl().isBlank()) {
            throw new NoSuchElementException("No receipt attached to this claim");
        }
        return decodeReceiptDataUri(claim.getReceiptUrl(), claimId);
    }

    private ReceiptFile decodeReceiptDataUri(String receiptUrl, UUID claimId) {
        // Standard "data:<mediaType>;base64,<payload>" — exactly what FileReader.readAsDataURL
        // produces on the frontend (AssetsExpensesPage's fileToBase64), and the only format this
        // field has ever been written in.
        int comma = receiptUrl.indexOf(',');
        if (!receiptUrl.startsWith("data:") || comma < 0) {
            throw new IllegalStateException("Receipt for claim " + claimId + " is not in the expected data-URI format");
        }
        String header = receiptUrl.substring(5, comma);
        String mediaType = header.contains(";") ? header.substring(0, header.indexOf(';')) : header;
        byte[] data = Base64.getDecoder().decode(receiptUrl.substring(comma + 1));
        String ext = switch (mediaType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "application/pdf" -> "pdf";
            default -> "bin";
        };
        String contentType = mediaType.isBlank() ? "application/octet-stream" : mediaType;
        return new ReceiptFile(data, contentType, "receipt-" + claimId + "." + ext);
    }

    // ── Employee tile summary ─────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> employeeTileSummary(String actorEmail) {
        UUID actorId = requireActor(actorEmail).getId();
        // Open claims = SUBMITTED or MANAGER_APPROVED
        List<ExpenseClaim> open = claimRepo.findByEmployeeUserIdInAndStatus(List.of(actorId), "SUBMITTED");
        open.addAll(claimRepo.findByEmployeeUserIdInAndStatus(List.of(actorId), "MANAGER_APPROVED"));
        BigDecimal openAmount = open.stream().map(ExpenseClaim::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Windowed on expenseDate (a plain LocalDate, the day the expense was actually incurred),
        // not the claim's decision/clearance timestamp - see the repository query's own comment.
        YearMonth now = YearMonth.now(ZoneId.of("UTC"));
        LocalDate from = now.atDay(1);
        LocalDate to = now.atEndOfMonth().plusDays(1);
        BigDecimal approvedAmt = claimRepo.sumApprovedThisMonth(actorId, from, to);
        long approvedCount = claimRepo.countApprovedThisMonth(actorId, from, to);

        Map<String, Object> result = new HashMap<>();
        result.put("openClaimCount", open.size());
        result.put("openClaimAmount", openAmount);
        result.put("approvedThisMonthAmount", approvedAmt);
        result.put("approvedThisMonthCount", approvedCount);
        return result;
    }

    // ── Manager stage: pending for approval ───────────────

    @Transactional(readOnly = true)
    public List<ExpenseClaimResponse> pendingForManager(String actorEmail) {
        User actor = requireActor(actorEmail);
        List<UUID> reportIds = historyRepo.findCurrentDirectReportIds(actor.getId());
        if (reportIds.isEmpty()) return List.of();
        return claimRepo.findByEmployeeUserIdInAndStatus(reportIds, "SUBMITTED").stream()
                .map(c -> toClaimResponse(c, categoryName(c.getCategoryId())))
                .collect(Collectors.toList());
    }

    @Transactional
    public ExpenseClaimResponse managerApprove(UUID claimId, String actorEmail) {
        User actor = requireActor(actorEmail);
        ExpenseClaim claim = requireClaimInStatus(claimId, "SUBMITTED");
        requireCurrentManagerOf(actor, claim.getEmployeeUserId());

        String before = auditSnapshot.toJson(Map.of("status", "SUBMITTED"));
        claim.setManagerDecidedBy(actor.getId());
        claim.setManagerDecidedAt(Instant.now());

        // Workflow Studio: a claim whose submission-time rule evaluation decided the HR/final
        // stage wasn't required (see ExpenseClaim.requiresSecondApproval) is fully cleared by
        // Manager approval alone — it skips MANAGER_APPROVED entirely and never appears in
        // pendingForFinalApprover, since that query only ever looks at status. This is the one
        // place the configured rule actually changes real approval routing, not just what a
        // dashboard displays.
        if (claim.isRequiresSecondApproval()) {
            claim.setStatus("MANAGER_APPROVED");
        } else {
            claim.setStatus("CLEARED_FOR_PAYROLL");
        }
        claimRepo.save(claim);
        String after = auditSnapshot.toJson(Map.of("status", claim.getStatus(), "managerDecidedBy", actor.getId().toString()));
        auditService.log(actor.getId(), "EXPENSE_MANAGER_APPROVED", claimId, before, after);

        String amountStr = String.format("₹%.2f", claim.getAmount());
        String catName = categoryName(claim.getCategoryId());
        if (claim.isRequiresSecondApproval()) {
            notificationService.send(claim.getEmployeeUserId(), "EXPENSE_MANAGER_APPROVED",
                    "Expense Claim Approved",
                    "Your " + catName + " claim for " + amountStr + " was approved by your manager.",
                    "/assets");
        } else {
            // No HR/final stage required for this claim — Manager approval was the only approval
            // needed, so tell the employee it's fully cleared rather than "approved by your
            // manager" (which would incorrectly imply an HR step still remains).
            notificationService.send(claim.getEmployeeUserId(), "EXPENSE_MANAGER_APPROVED",
                    "Expense Cleared for Payroll",
                    "Your " + catName + " claim for " + amountStr + " was approved by your manager and cleared for payroll — no further approval required.",
                    "/assets");
        }
        return toClaimResponse(claim, catName);
    }

    @Transactional
    public ExpenseClaimResponse managerReject(UUID claimId, String reason, String actorEmail) {
        User actor = requireActor(actorEmail);
        ExpenseClaim claim = requireClaimInStatus(claimId, "SUBMITTED");
        requireCurrentManagerOf(actor, claim.getEmployeeUserId());

        String before = auditSnapshot.toJson(Map.of("status", "SUBMITTED"));
        claim.setStatus("MANAGER_REJECTED");
        claim.setManagerDecidedBy(actor.getId());
        claim.setManagerDecidedAt(Instant.now());
        claim.setManagerRejectionReason(reason.trim());
        claimRepo.save(claim);
        String after = auditSnapshot.toJson(Map.of("status", "MANAGER_REJECTED", "managerRejectionReason", claim.getManagerRejectionReason()));
        auditService.log(actor.getId(), "EXPENSE_MANAGER_REJECTED", claimId, before, after);
        notificationService.send(claim.getEmployeeUserId(), "EXPENSE_MANAGER_REJECTED",
                "Expense Claim Rejected",
                "Your " + categoryName(claim.getCategoryId()) + " claim for " + String.format("₹%.2f", claim.getAmount()) + " was rejected. Reason: " + reason.trim(),
                "/assets");
        return toClaimResponse(claim, categoryName(claim.getCategoryId()));
    }

    // ── Final stage (HR Admin / Super Admin) ─────────────

    // ONEHR bug report: HR Admin/Super Admin couldn't see a claim in the Approval Center until
    // after Manager approval, blocking oversight of the manager stage entirely. Every other
    // approval-queue type in this app already gives HR/SA admin's blanket "all pending regardless
    // of stage" visibility (see RegularizationService.listPendingForApprover, and the class-level
    // doc comment on ApprovalCenterController) — Expense was the one outlier still hard-restricted
    // to MANAGER_APPROVED only. The backend was never actually blocking admins from acting at the
    // manager stage (managerApprove/managerReject already bypass the reporting-line check for
    // HR_ADMIN/SUPER_ADMIN — see requireCurrentManagerOf), so this was purely a visibility gap,
    // not a workflow-ordering safeguard: a SUBMITTED claim still can't skip straight to final
    // clearance (finalApprove/finalReject still require MANAGER_APPROVED via requireClaimInStatus).
    @Transactional(readOnly = true)
    public List<ExpenseClaimResponse> pendingForFinalApprover(String actorEmail) {
        requireFinalApprover(actorEmail);
        return claimRepo.findByStatusIn(List.of("SUBMITTED", "MANAGER_APPROVED")).stream()
                .map(c -> toClaimResponse(c, categoryName(c.getCategoryId())))
                .collect(Collectors.toList());
    }

    @Transactional
    public ExpenseClaimResponse finalApprove(UUID claimId, String actorEmail) {
        User actor = requireActor(actorEmail);
        requireFinalApproverRole(actor);
        ExpenseClaim claim = requireClaimInStatus(claimId, "MANAGER_APPROVED");

        String before = auditSnapshot.toJson(Map.of("status", "MANAGER_APPROVED"));
        claim.setStatus("CLEARED_FOR_PAYROLL");
        claim.setFinalDecidedBy(actor.getId());
        claim.setFinalDecidedAt(Instant.now());
        claimRepo.save(claim);
        String after = auditSnapshot.toJson(Map.of("status", "CLEARED_FOR_PAYROLL", "finalDecidedBy", actor.getId().toString()));
        auditService.log(actor.getId(), "EXPENSE_FINAL_APPROVED", claimId, before, after);
        notificationService.send(claim.getEmployeeUserId(), "EXPENSE_FINAL_APPROVED",
                "Expense Cleared for Payroll",
                "Your " + categoryName(claim.getCategoryId()) + " claim for " + String.format("₹%.2f", claim.getAmount()) + " has been cleared for payroll.",
                "/assets");
        return toClaimResponse(claim, categoryName(claim.getCategoryId()));
    }

    @Transactional
    public ExpenseClaimResponse finalReject(UUID claimId, String reason, String actorEmail) {
        User actor = requireActor(actorEmail);
        requireFinalApproverRole(actor);
        ExpenseClaim claim = requireClaimInStatus(claimId, "MANAGER_APPROVED");

        String before = auditSnapshot.toJson(Map.of("status", "MANAGER_APPROVED"));
        claim.setStatus("FINAL_REJECTED");
        claim.setFinalDecidedBy(actor.getId());
        claim.setFinalDecidedAt(Instant.now());
        claim.setFinalRejectionReason(reason.trim());
        claimRepo.save(claim);
        String after = auditSnapshot.toJson(Map.of("status", "FINAL_REJECTED", "finalRejectionReason", claim.getFinalRejectionReason()));
        auditService.log(actor.getId(), "EXPENSE_FINAL_REJECTED", claimId, before, after);
        notificationService.send(claim.getEmployeeUserId(), "EXPENSE_FINAL_REJECTED",
                "Expense Claim Rejected",
                "Your " + categoryName(claim.getCategoryId()) + " claim for " + String.format("₹%.2f", claim.getAmount()) + " was rejected by HR. Reason: " + reason.trim(),
                "/assets");
        return toClaimResponse(claim, categoryName(claim.getCategoryId()));
    }

    // ── Mark as Paid (HR Admin / Super Admin) ─────────────

    @Transactional(readOnly = true)
    public List<ExpenseClaimResponse> clearedForPayroll(String actorEmail) {
        requireFinalApprover(actorEmail);
        return claimRepo.findByStatus("CLEARED_FOR_PAYROLL").stream()
                .map(c -> toClaimResponse(c, categoryName(c.getCategoryId())))
                .collect(Collectors.toList());
    }

    @Transactional
    public ExpenseClaimResponse markPaid(UUID claimId, String actorEmail) {
        User actor = requireActor(actorEmail);
        requireFinalApproverRole(actor);
        ExpenseClaim claim = requireClaimInStatus(claimId, "CLEARED_FOR_PAYROLL");

        String before = auditSnapshot.toJson(Map.of("status", "CLEARED_FOR_PAYROLL"));
        claim.setStatus("PAID");
        claim.setPaidAt(Instant.now());
        claimRepo.save(claim);
        String after = auditSnapshot.toJson(Map.of("status", "PAID", "paidAt", claim.getPaidAt().toString()));
        auditService.log(actor.getId(), "EXPENSE_MARKED_PAID", claimId, before, after);
        notificationService.send(claim.getEmployeeUserId(), "EXPENSE_PAID",
                "Expense Paid",
                "Your " + categoryName(claim.getCategoryId()) + " claim for " + String.format("₹%.2f", claim.getAmount()) + " has been paid.",
                "/assets");
        return toClaimResponse(claim, categoryName(claim.getCategoryId()));
    }

    // ── Manager: all team claims (full lifecycle) ─────────

    @Transactional(readOnly = true)
    public List<ExpenseClaimResponse> allTeamClaims(String actorEmail) {
        User actor = requireActor(actorEmail);
        List<UUID> reportIds = historyRepo.findCurrentDirectReportIds(actor.getId());
        if (reportIds.isEmpty()) return List.of();
        return claimRepo.findByEmployeeUserIdInOrderByCreatedAtDesc(reportIds).stream()
                .map(c -> toClaimResponse(c, categoryName(c.getCategoryId())))
                .collect(Collectors.toList());
    }

    // ── Manager tile summary ──────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> managerTileSummary(String actorEmail) {
        User actor = requireActor(actorEmail);
        List<UUID> reportIds = historyRepo.findCurrentDirectReportIds(actor.getId());
        long pendingCount = reportIds.isEmpty() ? 0 :
                claimRepo.countByEmployeeUserIdInAndStatus(reportIds, "SUBMITTED");
        BigDecimal pendingAmount = reportIds.isEmpty() ? BigDecimal.ZERO :
                claimRepo.sumAmountByEmployeeUserIdInAndStatus(reportIds, "SUBMITTED");

        YearMonth now = YearMonth.now(ZoneId.of("UTC"));
        Instant from = now.atDay(1).atStartOfDay(ZoneId.of("UTC")).toInstant();
        Instant to = now.atEndOfMonth().plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant();
        List<ExpenseClaim> approvedByMe = claimRepo.findManagerApprovedInWindow(actor.getId(), from, to);
        BigDecimal approvedAmt = approvedByMe.stream().map(ExpenseClaim::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new HashMap<>();
        result.put("pendingCount", pendingCount);
        result.put("pendingAmount", pendingAmount);
        result.put("approvedThisMonthAmount", approvedAmt);
        result.put("approvedThisMonthCount", approvedByMe.size());
        return result;
    }

    // ── HR tile summary ───────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> hrTileSummary(String actorEmail) {
        requireFinalApprover(actorEmail);
        long pendingClearance = claimRepo.findByStatus("MANAGER_APPROVED").size();
        BigDecimal pendingAmount = claimRepo.sumAmountByStatus("MANAGER_APPROVED");
        return Map.of("pendingClearanceCount", pendingClearance, "pendingAmount", pendingAmount);
    }

    // ── Guards ────────────────────────────────────────────

    private void requireFinalApprover(String actorEmail) {
        requireFinalApproverRole(requireActor(actorEmail));
    }

    private void requireFinalApproverRole(User actor) {
        if (!isFinalApprover(actor)) {
            throw new AccessDeniedException("HR Admin or Super Admin role required");
        }
    }

    private boolean isFinalApprover(User actor) {
        return actor.getRoles().stream().anyMatch(r -> FINAL_APPROVER_ROLES.contains(r.getCode()));
    }

    private void requireCurrentManagerOf(User actor, UUID employeeUserId) {
        // HR_ADMIN/SUPER_ADMIN may decide the manager stage too, not just the final stage —
        // same override convention as every other approval workflow in the app (Regularization/
        // Asset/Overtime/AttendanceRequest/WebClockIn). ONEHR-140 follow-up: this manager-stage
        // check was the one place in this service missing that override, causing HR Admin
        // "Access Denied" on managerApprove/managerReject.
        if (isFinalApprover(actor)) return;
        EmployeeManagerHistory current = historyRepo.findByEmployeeUserIdAndEffectiveToIsNull(employeeUserId)
                .orElseThrow(() -> new AccessDeniedException("Employee has no assigned manager"));
        if (!current.getManagerUserId().equals(actor.getId())) {
            throw new AccessDeniedException("You are not the current manager of this employee");
        }
    }

    private ExpenseClaim requireClaimInStatus(UUID claimId, String expectedStatus) {
        ExpenseClaim claim = claimRepo.findById(claimId)
                .orElseThrow(() -> new IllegalArgumentException("Expense claim not found"));
        if (!expectedStatus.equals(claim.getStatus())) {
            throw new IllegalStateException(
                    "Claim is in status " + claim.getStatus() + ", expected " + expectedStatus);
        }
        return claim;
    }

    private User requireActor(String email) {
        return userRepo.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    // ── Mappers ───────────────────────────────────────────

    private ExpenseCategoryResponse toCategoryResponse(ExpenseCategory c) {
        return ExpenseCategoryResponse.builder()
                .id(c.getId()).name(c.getName())
                .requiresReceiptAbove(c.getRequiresReceiptAbove())
                .dailyLimit(c.getDailyLimit())
                .secondApprovalAbove(c.getSecondApprovalAbove())
                .build();
    }

    private ExpenseClaimResponse toClaimResponse(ExpenseClaim c, String catName) {
        return ExpenseClaimResponse.builder()
                .id(c.getId())
                .employeeUserId(c.getEmployeeUserId())
                .employeeName(employeeName(c.getEmployeeUserId()))
                .categoryId(c.getCategoryId())
                .categoryName(catName)
                .amount(c.getAmount())
                .expenseDate(c.getExpenseDate())
                .businessPurpose(c.getBusinessPurpose())
                .receiptUrl(c.getReceiptUrl())
                .status(c.getStatus())
                .managerDecidedByName(c.getManagerDecidedBy() != null ? employeeName(c.getManagerDecidedBy()) : null)
                .managerDecidedAt(c.getManagerDecidedAt())
                .managerRejectionReason(c.getManagerRejectionReason())
                .finalDecidedByName(c.getFinalDecidedBy() != null ? employeeName(c.getFinalDecidedBy()) : null)
                .finalDecidedAt(c.getFinalDecidedAt())
                .finalRejectionReason(c.getFinalRejectionReason())
                .paidAt(c.getPaidAt())
                .createdAt(c.getCreatedAt())
                .requiresSecondApproval(c.isRequiresSecondApproval())
                .build();
    }

    private String employeeName(UUID userId) {
        return employeeRepo.findById(userId).map(Employee::getFullName)
                .orElseGet(() -> userRepo.findById(userId).map(User::getEmail).orElse("Unknown"));
    }

    private String categoryName(Integer categoryId) {
        return categoryRepo.findById(categoryId).map(ExpenseCategory::getName).orElse("Unknown");
    }
}
