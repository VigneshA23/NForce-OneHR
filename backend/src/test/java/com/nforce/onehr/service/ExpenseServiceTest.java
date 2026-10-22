package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.expense.ExpenseClaimResponse;
import com.nforce.onehr.dto.expense.SubmitExpenseClaimRequest;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.ExpenseCategory;
import com.nforce.onehr.entity.ExpenseClaim;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.*;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.security.SeedMatrix;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExpenseServiceTest {

    @Mock private ExpenseClaimRepository claimRepo;
    @Mock private ExpenseCategoryRepository categoryRepo;
    @Mock private EmployeeManagerHistoryRepository historyRepo;
    @Mock private UserRepository userRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private NotificationService notificationService;
    @Mock private AttendanceProperties attendanceProperties;
    @Mock private ApprovalRuleEvaluationService approvalRuleEvaluationService;
    @Mock private RoleRepository roleRepo;
    // The REAL permission matrix from the migrations, plus test-only custom roles.
    @Spy private EffectivePermissionService effectivePermissionService = SeedMatrix.permissionService(Map.of(
            "CUSTOM_APPROVER", Map.of("expenses:APPROVE", "ALL"),
            "CUSTOM_CONFIG", Map.of("expenses:CONFIGURE", "ALL"),
            "CUSTOM_VIEWER", Map.of("expenses:VIEW", "ALL"),
            "CUSTOM_OVERRIDE", Map.of("expenses_override:APPROVE", "ALL")));

    @InjectMocks
    private ExpenseService expenseService;

    private User employeeUser;
    private ExpenseCategory category;
    private final String actorEmail = "employee@test.com";

    @BeforeEach
    void setUp() {
        employeeUser = User.builder()
                .id(UUID.randomUUID())
                .email(actorEmail)
                .build();

        category = ExpenseCategory.builder()
                .id(1)
                .name("Travel")
                .requiresReceiptAbove(new BigDecimal("100.00"))
                .dailyLimit(new BigDecimal("1000.00"))
                .build();

        lenient().when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        // Regression default: no Workflow Studio rule active — this app's one and only original
        // behavior (both stages, unconditionally). Individual tests below override this to
        // exercise the rule-driven skip-HR-stage path.
        lenient().when(approvalRuleEvaluationService.evaluateExpense(any()))
                .thenReturn(new ApprovalRuleEvaluationService.Decision(true, java.util.List.of("MANAGER", "HR_ADMIN"), null));
    }

    @Test
    void submit_futureExpenseDate_throwsIllegalArgumentException() {
        when(userRepo.findByEmail(actorEmail)).thenReturn(Optional.of(employeeUser));
        when(categoryRepo.findById(1)).thenReturn(Optional.of(category));

        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1);
        SubmitExpenseClaimRequest req = new SubmitExpenseClaimRequest();
        req.setCategoryId(1);
        req.setAmount(new BigDecimal("50.00"));
        req.setExpenseDate(tomorrow);
        req.setBusinessPurpose("Client meeting in future");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> expenseService.submit(req, actorEmail));
        assertEquals("Expense date cannot be in the future", ex.getMessage());

        verify(claimRepo, never()).save(any());
    }

    @Test
    void submit_pastExpenseDate_succeeds() {
        when(userRepo.findByEmail(actorEmail)).thenReturn(Optional.of(employeeUser));
        when(categoryRepo.findById(1)).thenReturn(Optional.of(category));
        when(claimRepo.save(any(ExpenseClaim.class))).thenAnswer(inv -> {
            ExpenseClaim c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        LocalDate pastDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(3);
        SubmitExpenseClaimRequest req = new SubmitExpenseClaimRequest();
        req.setCategoryId(1);
        req.setAmount(new BigDecimal("50.00"));
        req.setExpenseDate(pastDate);
        req.setBusinessPurpose("Past team lunch");

        ExpenseClaimResponse res = expenseService.submit(req, actorEmail);
        assertNotNull(res);
        assertEquals("SUBMITTED", res.getStatus());
        assertEquals(pastDate, res.getExpenseDate());
        verify(claimRepo, times(1)).save(any(ExpenseClaim.class));
    }

    @Test
    void submit_todayExpenseDate_succeeds() {
        when(userRepo.findByEmail(actorEmail)).thenReturn(Optional.of(employeeUser));
        when(categoryRepo.findById(1)).thenReturn(Optional.of(category));
        when(claimRepo.save(any(ExpenseClaim.class))).thenAnswer(inv -> {
            ExpenseClaim c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        SubmitExpenseClaimRequest req = new SubmitExpenseClaimRequest();
        req.setCategoryId(1);
        req.setAmount(new BigDecimal("50.00"));
        req.setExpenseDate(today);
        req.setBusinessPurpose("Today team lunch");

        ExpenseClaimResponse res = expenseService.submit(req, actorEmail);
        assertNotNull(res);
        assertEquals("SUBMITTED", res.getStatus());
        assertEquals(today, res.getExpenseDate());
        verify(claimRepo, times(1)).save(any(ExpenseClaim.class));
    }

    // ── Workflow Studio: regression — no active rule ─────────

    @Test
    void submit_noActiveRule_snapshotsLegacyDefault_requiresSecondApproval() {
        when(userRepo.findByEmail(actorEmail)).thenReturn(Optional.of(employeeUser));
        when(categoryRepo.findById(1)).thenReturn(Optional.of(category));
        when(claimRepo.save(any(ExpenseClaim.class))).thenAnswer(inv -> inv.getArgument(0));

        SubmitExpenseClaimRequest req = new SubmitExpenseClaimRequest();
        req.setCategoryId(1);
        req.setAmount(new BigDecimal("50.00"));
        req.setExpenseDate(LocalDate.now(ZoneId.of("Asia/Kolkata")));
        req.setBusinessPurpose("Regression check");

        ExpenseClaimResponse res = expenseService.submit(req, actorEmail);
        assertTrue(res.isRequiresSecondApproval(), "with no active rule, both stages must still be required (unchanged legacy behavior)");
    }

    // ── Workflow Studio: managerApprove branches on the submission-time snapshot ─────────

    private ExpenseClaim claimInStatus(String status, boolean requiresSecondApproval) {
        return ExpenseClaim.builder()
                .id(UUID.randomUUID())
                .employeeUserId(UUID.randomUUID())
                .categoryId(1)
                .amount(new BigDecimal("750.00"))
                .expenseDate(LocalDate.now())
                .businessPurpose("Test")
                .status(status)
                .requiresSecondApproval(requiresSecondApproval)
                .build();
    }

    private void stubManagerOf(User manager, UUID employeeUserId) {
        EmployeeManagerHistory history = EmployeeManagerHistory.builder()
                .employeeUserId(employeeUserId)
                .managerUserId(manager.getId())
                .build();
        when(historyRepo.findByEmployeeUserIdAndEffectiveToIsNull(employeeUserId)).thenReturn(Optional.of(history));
    }

    @Test
    void managerApprove_requiresSecondApproval_movesToManagerApprovedOnly() {
        User manager = User.builder().id(UUID.randomUUID()).email("manager@test.com").build();
        ExpenseClaim claim = claimInStatus("SUBMITTED", true);
        stubManagerOf(manager, claim.getEmployeeUserId());

        when(userRepo.findByEmail("manager@test.com")).thenReturn(Optional.of(manager));
        when(claimRepo.findById(claim.getId())).thenReturn(Optional.of(claim));
        when(claimRepo.save(any(ExpenseClaim.class))).thenAnswer(inv -> inv.getArgument(0));
        when(categoryRepo.findById(1)).thenReturn(Optional.of(category));

        ExpenseClaimResponse res = expenseService.managerApprove(claim.getId(), "manager@test.com");

        assertEquals("MANAGER_APPROVED", res.getStatus());
    }

    @Test
    void managerApprove_secondApprovalNotRequired_autoclearsToPayroll() {
        User manager = User.builder().id(UUID.randomUUID()).email("manager@test.com").build();
        ExpenseClaim claim = claimInStatus("SUBMITTED", false);
        stubManagerOf(manager, claim.getEmployeeUserId());

        when(userRepo.findByEmail("manager@test.com")).thenReturn(Optional.of(manager));
        when(claimRepo.findById(claim.getId())).thenReturn(Optional.of(claim));
        when(claimRepo.save(any(ExpenseClaim.class))).thenAnswer(inv -> inv.getArgument(0));
        when(categoryRepo.findById(1)).thenReturn(Optional.of(category));

        ExpenseClaimResponse res = expenseService.managerApprove(claim.getId(), "manager@test.com");

        // Skips MANAGER_APPROVED entirely — this is the actual routing change the rule engine
        // produces, not just a display difference: pendingForFinalApprover only ever queries by
        // status, so a claim landing directly on CLEARED_FOR_PAYROLL never surfaces at the HR stage.
        assertEquals("CLEARED_FOR_PAYROLL", res.getStatus());
    }

    // ── Manager-stage override recording (HR Admin/Super Admin acting as the Manager) ───────

    @Test
    void managerApprove_byActualReportingManager_recordsManagerRole() {
        User manager = User.builder().id(UUID.randomUUID()).email("manager@test.com").build();
        ExpenseClaim claim = claimInStatus("SUBMITTED", true);
        stubManagerOf(manager, claim.getEmployeeUserId());
        when(userRepo.findByEmail("manager@test.com")).thenReturn(Optional.of(manager));
        when(claimRepo.findById(claim.getId())).thenReturn(Optional.of(claim));
        when(claimRepo.save(any(ExpenseClaim.class))).thenAnswer(inv -> inv.getArgument(0));
        when(categoryRepo.findById(1)).thenReturn(Optional.of(category));

        ExpenseClaimResponse res = expenseService.managerApprove(claim.getId(), "manager@test.com");

        assertEquals("MANAGER", res.getManagerApprovedByRole());
    }

    @Test
    void managerApprove_byHrAdminOverride_recordsHrAdminRoleNotManager() {
        User hr = hrAdminUser();
        ExpenseClaim claim = claimInStatus("SUBMITTED", true);
        // The employee's actual manager is someone else entirely — HR is acting via the
        // isFinalApprover override in requireCurrentManagerOf, not as the real manager.
        stubManagerOf(User.builder().id(UUID.randomUUID()).email("realmanager@test.com").build(), claim.getEmployeeUserId());
        when(userRepo.findByEmail(hr.getEmail())).thenReturn(Optional.of(hr));
        when(claimRepo.findById(claim.getId())).thenReturn(Optional.of(claim));
        when(claimRepo.save(any(ExpenseClaim.class))).thenAnswer(inv -> inv.getArgument(0));
        when(categoryRepo.findById(1)).thenReturn(Optional.of(category));

        ExpenseClaimResponse res = expenseService.managerApprove(claim.getId(), hr.getEmail());

        assertEquals("MANAGER_APPROVED", res.getStatus());
        assertEquals("HR_ADMIN", res.getManagerApprovedByRole());
    }

    @Test
    void finalApprove_bySameHrAdminWhoApprovedManagerStage_isDenied() {
        User hr = hrAdminUser();
        ExpenseClaim claim = claimInStatus("MANAGER_APPROVED", true);
        claim.setManagerDecidedBy(hr.getId());
        claim.setManagerApprovedByRole("HR_ADMIN");
        stubFinalStage(hr, claim);

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> expenseService.finalApprove(claim.getId(), hr.getEmail()));
        assertEquals("MANAGER_APPROVED", claim.getStatus());
    }

    @Test
    void finalReject_bySameHrAdminWhoApprovedManagerStage_isDenied() {
        User hr = hrAdminUser();
        ExpenseClaim claim = claimInStatus("MANAGER_APPROVED", true);
        claim.setManagerDecidedBy(hr.getId());
        claim.setManagerApprovedByRole("HR_ADMIN");
        stubFinalStage(hr, claim);

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> expenseService.finalReject(claim.getId(), "not sufficiently documented", hr.getEmail()));
        assertEquals("MANAGER_APPROVED", claim.getStatus());
    }

    // ── View Receipt: backend role-based authorization ─────────

    // A minimal, real 1x1 transparent PNG, base64-encoded — exercised end-to-end through
    // Base64.getDecoder() the same way ExpenseService#decodeReceiptDataUri does, not a fake string.
    private static final String PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";

    private ExpenseClaim claimWithReceipt(UUID employeeUserId, String dataUri) {
        return ExpenseClaim.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeUserId)
                .categoryId(1)
                .amount(new BigDecimal("100.00"))
                .expenseDate(LocalDate.now())
                .businessPurpose("Test")
                .status("SUBMITTED")
                .receiptUrl(dataUri)
                .build();
    }

    private User hrAdminUser() {
        return userWith("hr@test.com", SeedMatrix.role("HR_ADMIN"));
    }

    private User superAdminUser() {
        return userWith("superadmin@test.com", SeedMatrix.role("SUPER_ADMIN"));
    }

    private static User userWith(String email, Role... roles) {
        return User.builder().id(UUID.randomUUID()).email(email).roles(new java.util.HashSet<>(List.of(roles))).build();
    }

    private static Role custom(String code) {
        return Role.builder().id(100 + Math.abs(code.hashCode() % 100)).code(code).displayName("Custom " + code)
                .system(false).active(true).build();
    }

    @Test
    void getReceipt_ownerOfClaim_allowed() {
        ExpenseClaim claim = claimWithReceipt(employeeUser.getId(), "data:image/png;base64," + PNG_BASE64);
        when(userRepo.findByEmail(actorEmail)).thenReturn(Optional.of(employeeUser));
        when(claimRepo.findById(claim.getId())).thenReturn(Optional.of(claim));

        ExpenseService.ReceiptFile receipt = expenseService.getReceipt(claim.getId(), actorEmail);

        assertEquals("image/png", receipt.contentType());
        assertTrue(receipt.data().length > 0);
    }

    @Test
    void getReceipt_currentManagerOfEmployee_allowed() {
        User manager = User.builder().id(UUID.randomUUID()).email("manager@test.com").build();
        ExpenseClaim claim = claimWithReceipt(UUID.randomUUID(), "data:image/png;base64," + PNG_BASE64);
        stubManagerOf(manager, claim.getEmployeeUserId());

        when(userRepo.findByEmail("manager@test.com")).thenReturn(Optional.of(manager));
        when(claimRepo.findById(claim.getId())).thenReturn(Optional.of(claim));

        assertDoesNotThrow(() -> expenseService.getReceipt(claim.getId(), "manager@test.com"));
    }

    @Test
    void getReceipt_unrelatedManager_deniedAccessDenied() {
        // A manager with no manager-history record at all for this employee — not their report.
        User unrelatedManager = User.builder().id(UUID.randomUUID()).email("other-manager@test.com").build();
        ExpenseClaim claim = claimWithReceipt(UUID.randomUUID(), "data:image/png;base64," + PNG_BASE64);

        when(userRepo.findByEmail("other-manager@test.com")).thenReturn(Optional.of(unrelatedManager));
        when(claimRepo.findById(claim.getId())).thenReturn(Optional.of(claim));
        when(historyRepo.findByEmployeeUserIdAndEffectiveToIsNull(claim.getEmployeeUserId())).thenReturn(Optional.empty());

        assertThrows(AccessDeniedException.class,
                () -> expenseService.getReceipt(claim.getId(), "other-manager@test.com"));
    }

    @Test
    void getReceipt_hrAdmin_allowedRegardlessOfManagerRelationship() {
        User hr = hrAdminUser();
        ExpenseClaim claim = claimWithReceipt(UUID.randomUUID(), "data:image/png;base64," + PNG_BASE64);

        when(userRepo.findByEmail("hr@test.com")).thenReturn(Optional.of(hr));
        when(claimRepo.findById(claim.getId())).thenReturn(Optional.of(claim));

        assertDoesNotThrow(() -> expenseService.getReceipt(claim.getId(), "hr@test.com"));
        // HR's blanket authority is checked before any manager-history lookup — see
        // requireCurrentManagerOf's own isFinalApprover short-circuit.
        verify(historyRepo, never()).findByEmployeeUserIdAndEffectiveToIsNull(any());
    }

    @Test
    void getReceipt_superAdmin_allowedRegardlessOfManagerRelationship() {
        User superAdmin = superAdminUser();
        ExpenseClaim claim = claimWithReceipt(UUID.randomUUID(), "data:image/png;base64," + PNG_BASE64);

        when(userRepo.findByEmail("superadmin@test.com")).thenReturn(Optional.of(superAdmin));
        when(claimRepo.findById(claim.getId())).thenReturn(Optional.of(claim));

        assertDoesNotThrow(() -> expenseService.getReceipt(claim.getId(), "superadmin@test.com"));
    }

    @Test
    void getReceipt_employeeViewingSomeoneElsesClaim_deniedWhenNotTheirManager() {
        // A different EMPLOYEE (no manager/HR/SA role at all) trying to view a co-worker's receipt.
        User otherEmployee = User.builder().id(UUID.randomUUID()).email("coworker@test.com").build();
        ExpenseClaim claim = claimWithReceipt(UUID.randomUUID(), "data:image/png;base64," + PNG_BASE64);

        when(userRepo.findByEmail("coworker@test.com")).thenReturn(Optional.of(otherEmployee));
        when(claimRepo.findById(claim.getId())).thenReturn(Optional.of(claim));
        when(historyRepo.findByEmployeeUserIdAndEffectiveToIsNull(claim.getEmployeeUserId())).thenReturn(Optional.empty());

        assertThrows(AccessDeniedException.class,
                () -> expenseService.getReceipt(claim.getId(), "coworker@test.com"));
    }

    @Test
    void getReceipt_noReceiptAttached_throwsNoSuchElementWithClearMessage() {
        ExpenseClaim claim = claimWithReceipt(employeeUser.getId(), null);
        when(userRepo.findByEmail(actorEmail)).thenReturn(Optional.of(employeeUser));
        when(claimRepo.findById(claim.getId())).thenReturn(Optional.of(claim));

        NoSuchElementException ex = assertThrows(NoSuchElementException.class,
                () -> expenseService.getReceipt(claim.getId(), actorEmail));
        assertEquals("No receipt attached to this claim", ex.getMessage());
    }

    @Test
    void getReceipt_claimNotFound_throwsNoSuchElement() {
        UUID missingId = UUID.randomUUID();
        when(userRepo.findByEmail(actorEmail)).thenReturn(Optional.of(employeeUser));
        when(claimRepo.findById(missingId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> expenseService.getReceipt(missingId, actorEmail));
    }

    @Test
    void getReceipt_pdfReceipt_decodesWithPdfContentType() {
        String pdfBase64 = java.util.Base64.getEncoder().encodeToString("%PDF-1.4 fake pdf bytes".getBytes());
        ExpenseClaim claim = claimWithReceipt(employeeUser.getId(), "data:application/pdf;base64," + pdfBase64);
        when(userRepo.findByEmail(actorEmail)).thenReturn(Optional.of(employeeUser));
        when(claimRepo.findById(claim.getId())).thenReturn(Optional.of(claim));

        ExpenseService.ReceiptFile receipt = expenseService.getReceipt(claim.getId(), actorEmail);

        assertEquals("application/pdf", receipt.contentType());
        assertTrue(receipt.fileName().endsWith(".pdf"));
        assertEquals("%PDF-1.4 fake pdf bytes", new String(receipt.data()));
    }

    // ── "Approved This Month" tile: windowed by expenseDate, not the clearance/payment date ──

    @Test
    void employeeTileSummary_windowsApprovedThisMonthByExpenseDate_notFinalDecidedAt() {
        when(userRepo.findByEmail(actorEmail)).thenReturn(Optional.of(employeeUser));
        when(claimRepo.findByEmployeeUserIdInAndStatus(List.of(employeeUser.getId()), "SUBMITTED"))
                .thenReturn(new ArrayList<>());
        when(claimRepo.findByEmployeeUserIdInAndStatus(List.of(employeeUser.getId()), "MANAGER_APPROVED"))
                .thenReturn(new ArrayList<>());
        // A claim incurred this month but only cleared next month (or vice versa) must still be
        // windowed on when it was spent - the repository query itself is the actual fix (this test
        // only pins down the LocalDate window ExpenseService hands it, since sumApprovedThisMonth/
        // countApprovedThisMonth are mocked here, not exercised against real JPA date semantics).
        when(claimRepo.sumApprovedThisMonth(eq(employeeUser.getId()), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(new BigDecimal("12300.00"));
        when(claimRepo.countApprovedThisMonth(eq(employeeUser.getId()), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(4L);

        Map<String, Object> result = expenseService.employeeTileSummary(actorEmail);

        assertEquals(new BigDecimal("12300.00"), result.get("approvedThisMonthAmount"));
        assertEquals(4L, result.get("approvedThisMonthCount"));

        ArgumentCaptor<LocalDate> fromCap = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> toCap = ArgumentCaptor.forClass(LocalDate.class);
        verify(claimRepo).sumApprovedThisMonth(eq(employeeUser.getId()), fromCap.capture(), toCap.capture());
        YearMonth thisMonth = YearMonth.now(ZoneId.of("UTC"));
        assertEquals(thisMonth.atDay(1), fromCap.getValue());
        assertEquals(thisMonth.atEndOfMonth().plusDays(1), toCap.getValue());
    }

    // ── Workflow Studio multi-layer approval: Manager -> HR Admin -> Super Admin ─────────

    private ExpenseClaim threeStageClaimAwaiting(String pendingStage) {
        ExpenseClaim claim = claimInStatus("MANAGER_APPROVED", true);
        claim.setApprovalStages("MANAGER,HR_ADMIN,SUPER_ADMIN");
        claim.setPendingFinalStage(pendingStage);
        return claim;
    }

    // pendingForFinalApprover reads the lighter ExpenseClaimSummary projection (see
    // ExpenseClaimRepository#findSummaryByStatusIn), not full entities — this mirrors an
    // ExpenseClaim onto the same fields for that one test.
    private ExpenseClaimSummary toSummary(ExpenseClaim c) {
        return new ExpenseClaimSummary(c.getId(), c.getEmployeeUserId(), c.getCategoryId(), c.getAmount(),
                c.getExpenseDate(), c.getBusinessPurpose(), c.getStatus(), c.getManagerDecidedBy(), c.getManagerDecidedAt(),
                c.getManagerRejectionReason(), c.getManagerApprovedByRole(), c.getFinalDecidedBy(), c.getFinalDecidedAt(), c.getFinalRejectionReason(),
                c.getPaidAt(), c.getCreatedAt(), c.isRequiresSecondApproval(), c.getPendingFinalStage());
    }

    private void stubFinalStage(User actor, ExpenseClaim claim) {
        when(userRepo.findByEmail(actor.getEmail())).thenReturn(Optional.of(actor));
        when(claimRepo.findById(claim.getId())).thenReturn(Optional.of(claim));
        lenient().when(claimRepo.save(any(ExpenseClaim.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(categoryRepo.findById(1)).thenReturn(Optional.of(category));
    }

    @Test
    void managerApprove_threeStageClaim_awaitsHrAdminFirst() {
        User manager = User.builder().id(UUID.randomUUID()).email("manager@test.com").build();
        ExpenseClaim claim = claimInStatus("SUBMITTED", true);
        claim.setApprovalStages("MANAGER,HR_ADMIN,SUPER_ADMIN");
        stubManagerOf(manager, claim.getEmployeeUserId());
        stubFinalStage(manager, claim);

        ExpenseClaimResponse res = expenseService.managerApprove(claim.getId(), "manager@test.com");

        assertEquals("MANAGER_APPROVED", res.getStatus());
        assertEquals("HR_ADMIN", res.getPendingFinalStage());
    }

    @Test
    void finalApprove_hrAdminOnThreeStageClaim_movesToSuperAdmin_notClearedForPayroll() {
        User hr = hrAdminUser();
        ExpenseClaim claim = threeStageClaimAwaiting("HR_ADMIN");
        stubFinalStage(hr, claim);

        ExpenseClaimResponse res = expenseService.finalApprove(claim.getId(), hr.getEmail());

        assertEquals("MANAGER_APPROVED", res.getStatus(), "HR approval alone must not clear a claim that also needs Super Admin");
        assertEquals("SUPER_ADMIN", res.getPendingFinalStage());
    }

    @Test
    void finalApprove_superAdminAtLastStage_clearsForPayroll() {
        User sa = superAdminUser();
        ExpenseClaim claim = threeStageClaimAwaiting("SUPER_ADMIN");
        stubFinalStage(sa, claim);

        ExpenseClaimResponse res = expenseService.finalApprove(claim.getId(), sa.getEmail());

        assertEquals("CLEARED_FOR_PAYROLL", res.getStatus());
        assertNull(res.getPendingFinalStage());
    }

    @Test
    void finalApprove_hrAdminAtSuperAdminStage_denied() {
        User hr = hrAdminUser();
        ExpenseClaim claim = threeStageClaimAwaiting("SUPER_ADMIN");
        stubFinalStage(hr, claim);

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> expenseService.finalApprove(claim.getId(), hr.getEmail()));
        assertEquals("MANAGER_APPROVED", claim.getStatus());
    }

    @Test
    void pendingForFinalApprover_claimAwaitingSuperAdmin_shownToSuperAdmin_hiddenFromHrAdmin() {
        ExpenseClaim claim = threeStageClaimAwaiting("SUPER_ADMIN");
        when(claimRepo.findSummaryByStatusIn(any())).thenReturn(List.of(toSummary(claim)));
        lenient().when(categoryRepo.findById(1)).thenReturn(Optional.of(category));
        User hr = hrAdminUser();
        User sa = superAdminUser();
        when(userRepo.findByEmail(hr.getEmail())).thenReturn(Optional.of(hr));
        when(userRepo.findByEmail(sa.getEmail())).thenReturn(Optional.of(sa));

        assertTrue(expenseService.pendingForFinalApprover(hr.getEmail()).isEmpty());
        assertEquals(1, expenseService.pendingForFinalApprover(sa.getEmail()).size());
    }

    @Test
    void finalApprove_legacyClaimWithoutStageList_hrAdminStillClearsInOneStep() {
        User hr = hrAdminUser();
        ExpenseClaim claim = claimInStatus("MANAGER_APPROVED", true);
        stubFinalStage(hr, claim);

        assertEquals("CLEARED_FOR_PAYROLL", expenseService.finalApprove(claim.getId(), hr.getEmail()).getStatus());
    }

    // ── Permission migration (V236): system roles unchanged, custom roles by permission ─────────

    private com.nforce.onehr.dto.expense.CreateExpenseCategoryRequest categoryRequest() {
        com.nforce.onehr.dto.expense.CreateExpenseCategoryRequest req = new com.nforce.onehr.dto.expense.CreateExpenseCategoryRequest();
        req.setName("Meals");
        req.setRequiresReceiptAbove(new BigDecimal("100"));
        return req;
    }

    private void assertCreateCategory(User actor, boolean allowed) {
        when(userRepo.findByEmail(actor.getEmail())).thenReturn(Optional.of(actor));
        if (allowed) {
            when(categoryRepo.save(any(ExpenseCategory.class))).thenAnswer(inv -> inv.getArgument(0));
            assertDoesNotThrow(() -> expenseService.createCategory(categoryRequest(), actor.getEmail()));
        } else {
            assertThrows(AccessDeniedException.class, () -> expenseService.createCategory(categoryRequest(), actor.getEmail()));
            verify(categoryRepo, never()).save(any());
        }
    }

    @Test void createCategory_hrAdmin_allowed() { assertCreateCategory(hrAdminUser(), true); }
    @Test void createCategory_superAdmin_allowed() { assertCreateCategory(superAdminUser(), true); }
    @Test void createCategory_manager_denied() { assertCreateCategory(userWith("m@test.com", SeedMatrix.role("MANAGER"), SeedMatrix.role("EMPLOYEE")), false); }
    @Test void createCategory_employee_denied() { assertCreateCategory(userWith("e@test.com", SeedMatrix.role("EMPLOYEE")), false); }
    @Test void createCategory_customRoleWithConfigure_allowed() { assertCreateCategory(userWith("c@test.com", custom("CUSTOM_CONFIG")), true); }
    @Test void createCategory_customRoleWithoutConfigure_denied() { assertCreateCategory(userWith("c@test.com", custom("CUSTOM_APPROVER")), false); }

    @Test
    void markPaid_manager_denied_beforeAnyClaimLookup() {
        User manager = userWith("m@test.com", SeedMatrix.role("MANAGER"));
        when(userRepo.findByEmail(manager.getEmail())).thenReturn(Optional.of(manager));

        assertThrows(AccessDeniedException.class, () -> expenseService.markPaid(UUID.randomUUID(), manager.getEmail()));
        verify(claimRepo, never()).findById(any());
    }

    @Test
    void markPaid_customRoleWithApprove_allowed_viewOnlyCustomRoleDenied() {
        ExpenseClaim claim = claimInStatus("CLEARED_FOR_PAYROLL", true);
        User approver = userWith("a@test.com", custom("CUSTOM_APPROVER"));
        stubFinalStage(approver, claim);
        assertEquals("PAID", expenseService.markPaid(claim.getId(), approver.getEmail()).getStatus());

        User viewer = userWith("v@test.com", custom("CUSTOM_VIEWER"));
        when(userRepo.findByEmail(viewer.getEmail())).thenReturn(Optional.of(viewer));
        assertThrows(AccessDeniedException.class, () -> expenseService.markPaid(claim.getId(), viewer.getEmail()));
    }

    @Test
    void clearedForPayroll_hrAndCustomViewerAllowed_managerAndPlainCustomDenied() {
        User hr = hrAdminUser();
        User viewer = userWith("v@test.com", custom("CUSTOM_VIEWER"));
        User manager = userWith("m@test.com", SeedMatrix.role("MANAGER"));
        User plain = userWith("p@test.com", custom("CUSTOM_NONE"));
        for (User u : List.of(hr, viewer, manager, plain)) when(userRepo.findByEmail(u.getEmail())).thenReturn(Optional.of(u));
        when(claimRepo.findSummaryByStatus("CLEARED_FOR_PAYROLL")).thenReturn(List.of());

        assertDoesNotThrow(() -> expenseService.clearedForPayroll(hr.getEmail()));
        assertDoesNotThrow(() -> expenseService.clearedForPayroll(viewer.getEmail()));
        assertThrows(AccessDeniedException.class, () -> expenseService.clearedForPayroll(manager.getEmail()));
        assertThrows(AccessDeniedException.class, () -> expenseService.clearedForPayroll(plain.getEmail()));
    }

    @Test
    void pendingForFinalApprover_managerAndEmployee_stillDenied() {
        User manager = userWith("m@test.com", SeedMatrix.role("MANAGER"), SeedMatrix.role("EMPLOYEE"));
        when(userRepo.findByEmail(manager.getEmail())).thenReturn(Optional.of(manager));

        assertThrows(AccessDeniedException.class, () -> expenseService.pendingForFinalApprover(manager.getEmail()));
        assertFalse(expenseService.hasFinalApproverQueue(manager.getEmail()));
    }

    @Test
    void managerApprove_customRoleWithApproveAll_overridesManagerStage_likeHrAdmin() {
        User approver = userWith("a@test.com", custom("CUSTOM_APPROVER"));
        ExpenseClaim claim = claimInStatus("SUBMITTED", true);
        stubManagerOf(User.builder().id(UUID.randomUUID()).build(), claim.getEmployeeUserId());
        stubFinalStage(approver, claim);

        assertEquals("MANAGER_APPROVED", expenseService.managerApprove(claim.getId(), approver.getEmail()).getStatus());
    }

    @Test
    void managerApprove_customRoleWithoutApprove_notTheManager_denied() {
        User plain = userWith("p@test.com", custom("CUSTOM_VIEWER"));
        ExpenseClaim claim = claimInStatus("SUBMITTED", true);
        stubManagerOf(User.builder().id(UUID.randomUUID()).build(), claim.getEmployeeUserId());
        stubFinalStage(plain, claim);

        assertThrows(AccessDeniedException.class, () -> expenseService.managerApprove(claim.getId(), plain.getEmail()));
        assertEquals("SUBMITTED", claim.getStatus());
    }

    @Test
    void finalApprove_customRoleWithApproveAll_actsAtHrStage_butNotSuperAdminStage() {
        User approver = userWith("a@test.com", custom("CUSTOM_APPROVER"));
        ExpenseClaim legacy = claimInStatus("MANAGER_APPROVED", true);
        stubFinalStage(approver, legacy);
        assertEquals("CLEARED_FOR_PAYROLL", expenseService.finalApprove(legacy.getId(), approver.getEmail()).getStatus());

        ExpenseClaim atSa = threeStageClaimAwaiting("SUPER_ADMIN");
        when(claimRepo.findById(atSa.getId())).thenReturn(Optional.of(atSa));
        assertThrows(AccessDeniedException.class, () -> expenseService.finalApprove(atSa.getId(), approver.getEmail()));
    }

    @Test
    void finalApprove_customRoleWithoutPermission_atHrStage_denied() {
        User plain = userWith("p@test.com", custom("CUSTOM_VIEWER"));
        ExpenseClaim claim = threeStageClaimAwaiting("HR_ADMIN");
        stubFinalStage(plain, claim);

        assertThrows(AccessDeniedException.class, () -> expenseService.finalApprove(claim.getId(), plain.getEmail()));
        assertEquals("HR_ADMIN", claim.getPendingFinalStage());
    }

    @Test
    void finalApprove_customRoleWithOverride_actsAtSuperAdminStage_likeSuperAdmin() {
        User overrider = userWith("o@test.com", custom("CUSTOM_OVERRIDE"));
        ExpenseClaim claim = threeStageClaimAwaiting("SUPER_ADMIN");
        stubFinalStage(overrider, claim);

        assertEquals("CLEARED_FOR_PAYROLL", expenseService.finalApprove(claim.getId(), overrider.getEmail()).getStatus());
    }

    @Test
    void finalApprove_superAdminAtHrStage_alsoSatisfiesItsOwnLaterStage_unchanged() {
        User sa = superAdminUser();
        ExpenseClaim claim = threeStageClaimAwaiting("HR_ADMIN");
        stubFinalStage(sa, claim);

        assertEquals("CLEARED_FOR_PAYROLL", expenseService.finalApprove(claim.getId(), sa.getEmail()).getStatus());
    }

    @Test
    void finalApprove_superAdminAtSuperAdminStage_doesNotSkipALaterHrStage_unchanged() {
        // Pins the one role-code check deliberately kept (holdsStage for HR_ADMIN): before V236 a
        // Super Admin without the HR_ADMIN role did not auto-clear a later HR Admin stage.
        User sa = superAdminUser();
        ExpenseClaim claim = claimInStatus("MANAGER_APPROVED", true);
        claim.setApprovalStages("MANAGER,SUPER_ADMIN,HR_ADMIN");
        claim.setPendingFinalStage("SUPER_ADMIN");
        stubFinalStage(sa, claim);

        ExpenseClaimResponse res = expenseService.finalApprove(claim.getId(), sa.getEmail());
        assertEquals("MANAGER_APPROVED", res.getStatus());
        assertEquals("HR_ADMIN", res.getPendingFinalStage());
    }

    // ── Custom role as a Workflow Studio stage (V236) ─────────

    private ExpenseClaim customStageClaim(String stages, String pending) {
        ExpenseClaim claim = claimInStatus("MANAGER_APPROVED", true);
        claim.setApprovalStages(stages);
        claim.setPendingFinalStage(pending);
        return claim;
    }

    @Test
    void managerApprove_customStageRule_awaitsTheCustomRole() {
        User manager = User.builder().id(UUID.randomUUID()).email("manager@test.com").build();
        ExpenseClaim claim = claimInStatus("SUBMITTED", true);
        claim.setApprovalStages("MANAGER,CUSTOM_FIN");
        stubManagerOf(manager, claim.getEmployeeUserId());
        stubFinalStage(manager, claim);

        assertEquals("CUSTOM_FIN", expenseService.managerApprove(claim.getId(), manager.getEmail()).getPendingFinalStage());
    }

    @Test
    void finalApprove_customStage_holderWithNoPermissionsClears() {
        User holder = userWith("fin@test.com", custom("CUSTOM_FIN"));
        ExpenseClaim claim = customStageClaim("MANAGER,CUSTOM_FIN", "CUSTOM_FIN");
        stubFinalStage(holder, claim);

        assertEquals("CLEARED_FOR_PAYROLL", expenseService.finalApprove(claim.getId(), holder.getEmail()).getStatus());
    }

    @Test
    void finalApprove_customStage_hrAdminAndOtherCustomRoleDenied_superAdminOverrides() {
        ExpenseClaim claim = customStageClaim("MANAGER,CUSTOM_FIN", "CUSTOM_FIN");
        when(roleRepo.findByCode("CUSTOM_FIN")).thenReturn(Optional.of(custom("CUSTOM_FIN")));
        User hr = hrAdminUser();
        stubFinalStage(hr, claim);
        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () -> expenseService.finalReject(claim.getId(), "no", hr.getEmail()));
        assertTrue(ex.getMessage().contains("Custom CUSTOM_FIN"), "labelled with the role's display name: " + ex.getMessage());

        User other = userWith("other@test.com", custom("CUSTOM_OTHER"));
        when(userRepo.findByEmail(other.getEmail())).thenReturn(Optional.of(other));
        assertThrows(AccessDeniedException.class, () -> expenseService.finalApprove(claim.getId(), other.getEmail()));

        User sa = superAdminUser();
        when(userRepo.findByEmail(sa.getEmail())).thenReturn(Optional.of(sa));
        assertEquals("CLEARED_FOR_PAYROLL", expenseService.finalApprove(claim.getId(), sa.getEmail()).getStatus());
    }

    @Test
    void finalApprove_inactiveCustomRoleHolder_denied() {
        Role inactive = custom("CUSTOM_FIN");
        inactive.setActive(false);
        User holder = userWith("fin@test.com", inactive);
        ExpenseClaim claim = customStageClaim("MANAGER,CUSTOM_FIN", "CUSTOM_FIN");
        when(userRepo.findByEmail(holder.getEmail())).thenReturn(Optional.of(holder));

        assertThrows(AccessDeniedException.class, () -> expenseService.finalApprove(claim.getId(), holder.getEmail()));
    }

    @Test
    void finalApprove_customStageThenSuperAdmin_movesOnToSuperAdmin() {
        User holder = userWith("fin@test.com", custom("CUSTOM_FIN"));
        ExpenseClaim claim = customStageClaim("MANAGER,CUSTOM_FIN,SUPER_ADMIN", "CUSTOM_FIN");
        stubFinalStage(holder, claim);

        ExpenseClaimResponse res = expenseService.finalApprove(claim.getId(), holder.getEmail());
        assertEquals("MANAGER_APPROVED", res.getStatus());
        assertEquals("SUPER_ADMIN", res.getPendingFinalStage());
    }

    @Test
    void pendingForFinalApprover_customStageHolder_seesOnlyClaimsAtTheirStage() {
        User holder = userWith("fin@test.com", custom("CUSTOM_FIN"));
        when(userRepo.findByEmail(holder.getEmail())).thenReturn(Optional.of(holder));
        ExpenseClaim mine = customStageClaim("MANAGER,CUSTOM_FIN", "CUSTOM_FIN");
        ExpenseClaim hrStage = threeStageClaimAwaiting("HR_ADMIN");
        ExpenseClaim submitted = claimInStatus("SUBMITTED", true);
        when(claimRepo.findSummaryByStatusIn(any())).thenReturn(List.of(toSummary(mine), toSummary(hrStage), toSummary(submitted)));

        List<ExpenseClaimResponse> res = expenseService.pendingForFinalApprover(holder.getEmail());
        assertEquals(List.of(mine.getId()), res.stream().map(ExpenseClaimResponse::getId).toList());
        assertTrue(expenseService.hasFinalApproverQueue(holder.getEmail()));
    }

    @Test
    void pendingForFinalApprover_hrAdmin_stillSeesSubmittedAndHrStage_notCustomStage() {
        User hr = hrAdminUser();
        when(userRepo.findByEmail(hr.getEmail())).thenReturn(Optional.of(hr));
        ExpenseClaim custom = customStageClaim("MANAGER,CUSTOM_FIN", "CUSTOM_FIN");
        ExpenseClaim hrStage = threeStageClaimAwaiting("HR_ADMIN");
        ExpenseClaim submitted = claimInStatus("SUBMITTED", true);
        when(claimRepo.findSummaryByStatusIn(any())).thenReturn(List.of(toSummary(custom), toSummary(hrStage), toSummary(submitted)));

        assertEquals(Set.of(hrStage.getId(), submitted.getId()),
                expenseService.pendingForFinalApprover(hr.getEmail()).stream().map(ExpenseClaimResponse::getId).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void getReceipt_customStageHolder_allowedAtTheirStageOnly() {
        User holder = userWith("fin@test.com", custom("CUSTOM_FIN"));
        when(userRepo.findByEmail(holder.getEmail())).thenReturn(Optional.of(holder));
        ExpenseClaim atStage = claimWithReceipt(UUID.randomUUID(), "data:image/png;base64," + PNG_BASE64);
        atStage.setStatus("MANAGER_APPROVED");
        atStage.setPendingFinalStage("CUSTOM_FIN");
        when(claimRepo.findById(atStage.getId())).thenReturn(Optional.of(atStage));
        assertDoesNotThrow(() -> expenseService.getReceipt(atStage.getId(), holder.getEmail()));

        ExpenseClaim submitted = claimWithReceipt(UUID.randomUUID(), "data:image/png;base64," + PNG_BASE64);
        when(claimRepo.findById(submitted.getId())).thenReturn(Optional.of(submitted));
        when(historyRepo.findByEmployeeUserIdAndEffectiveToIsNull(submitted.getEmployeeUserId())).thenReturn(Optional.empty());
        assertThrows(AccessDeniedException.class, () -> expenseService.getReceipt(submitted.getId(), holder.getEmail()));
    }
}
