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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
        return User.builder().id(UUID.randomUUID()).email("hr@test.com")
                .roles(Set.of(Role.builder().id(1).code("HR_ADMIN").displayName("HR Admin").build()))
                .build();
    }

    private User superAdminUser() {
        return User.builder().id(UUID.randomUUID()).email("superadmin@test.com")
                .roles(Set.of(Role.builder().id(2).code("SUPER_ADMIN").displayName("Super Admin").build()))
                .build();
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
}
