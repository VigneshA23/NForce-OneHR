package com.nforce.onehr.service;

import com.nforce.onehr.dto.EmployeeResponse;
import com.nforce.onehr.dto.asset.AssetAssignmentResponse;
import com.nforce.onehr.dto.doc.RequiredDocumentDto;
import com.nforce.onehr.dto.onboarding.OnboardingChecklistDetailDto;
import com.nforce.onehr.dto.onboarding.OnboardingChecklistSummaryDto;
import com.nforce.onehr.dto.onboarding.StartOnboardingRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.OnboardingChecklist;
import com.nforce.onehr.entity.OnboardingChecklistItem;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Onboarding lifecycle: Pending (no checklist) / Started (IN_PROGRESS) / Successfully Onboarded
 * (COMPLETED, only via an explicit complete action — never as a side effect of a read).
 */
@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    @Mock private OnboardingChecklistRepository checklistRepo;
    @Mock private OnboardingChecklistItemRepository itemRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private EmployeeManagerHistoryRepository historyRepo;
    @Mock private UserRepository userRepo;
    @Mock private EmployeeService employeeService;
    @Mock private DocumentService documentService;
    @Mock private AssetService assetService;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;

    private OnboardingService onboardingService;

    private static final String ADMIN_EMAIL = "hr.admin@test.com";
    private UUID adminId;
    private UUID employeeId;
    private Employee employee;
    private UUID checklistId;

    @BeforeEach
    void setUp() {
        onboardingService = new OnboardingService(checklistRepo, itemRepo, employeeRepo, historyRepo,
                userRepo, employeeService, documentService, assetService, auditService, notificationService);

        adminId = UUID.randomUUID();
        User admin = User.builder().id(adminId).email(ADMIN_EMAIL)
                .roles(new HashSet<>(Set.of(role("HR_ADMIN")))).build();
        lenient().when(userRepo.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(admin));

        employeeId = UUID.randomUUID();
        employee = Employee.builder()
                .userId(employeeId)
                .employeeCode("E100")
                .fullName("Jane Doe")
                .joiningDate(LocalDate.now().minusDays(10))
                .build();
        lenient().when(employeeRepo.findById(employeeId)).thenReturn(Optional.of(employee));

        checklistId = UUID.randomUUID();

        lenient().when(historyRepo.findByEmployeeUserIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());
    }

    private static Role role(String code) {
        int id = switch (code) {
            case "EMPLOYEE" -> 1;
            case "MANAGER" -> 2;
            case "HR_ADMIN" -> 3;
            case "SUPER_ADMIN" -> 4;
            default -> throw new IllegalArgumentException("Unknown role code: " + code);
        };
        return Role.builder().id(id).code(code).displayName(code).build();
    }

    private OnboardingChecklist inProgressChecklist() {
        return OnboardingChecklist.builder()
                .id(checklistId)
                .employeeUserId(employeeId)
                .startedBy(adminId)
                .status("IN_PROGRESS")
                .startedAt(Instant.now())
                .build();
    }

    /** All manual items done, and both auto (laptop + access card) + all documents verified. */
    private void stubAllItemsDone() {
        when(itemRepo.findByChecklistIdOrderByDueDateAsc(checklistId)).thenReturn(List.of(
                doneItem("PRE_BOARDING", "OFFER_LETTER_COUNTERSIGNED"),
                doneItem("PRE_BOARDING", "WELCOME_EMAIL_SENT"),
                doneItem("PRE_BOARDING", "BUDDY_ASSIGNED"),
                doneItem("PRE_BOARDING", "WORKSTATION_READY"),
                doneItem("SETUP", "EMAIL_ACCESS_CREATED"),
                doneItem("SETUP", "PAYROLL_ENROLLED")
        ));
        when(assetService.currentAssignmentsForEmployee(employeeId)).thenReturn(List.of(
                assignment("Laptop"), assignment("Access Card")
        ));
        when(documentService.requiredDocumentsFor(employeeId)).thenReturn(List.of(
                new RequiredDocumentDto(1, "PAN Card", true, false, true, "VERIFIED", false)
        ));
    }

    /** One manual item left undone — checklist is not ready to complete. */
    private void stubOneItemPending() {
        when(itemRepo.findByChecklistIdOrderByDueDateAsc(checklistId)).thenReturn(List.of(
                doneItem("PRE_BOARDING", "OFFER_LETTER_COUNTERSIGNED"),
                pendingItem("PRE_BOARDING", "WELCOME_EMAIL_SENT"),
                doneItem("PRE_BOARDING", "BUDDY_ASSIGNED"),
                doneItem("PRE_BOARDING", "WORKSTATION_READY"),
                doneItem("SETUP", "EMAIL_ACCESS_CREATED"),
                doneItem("SETUP", "PAYROLL_ENROLLED")
        ));
        when(assetService.currentAssignmentsForEmployee(employeeId)).thenReturn(List.of(
                assignment("Laptop"), assignment("Access Card")
        ));
        when(documentService.requiredDocumentsFor(employeeId)).thenReturn(List.of(
                new RequiredDocumentDto(1, "PAN Card", true, false, true, "VERIFIED", false)
        ));
    }

    private OnboardingChecklistItem doneItem(String category, String key) {
        return OnboardingChecklistItem.builder().checklistId(checklistId).category(category).itemKey(key)
                .label(key).done(true).doneAt(Instant.now()).doneBy(adminId).build();
    }

    private OnboardingChecklistItem pendingItem(String category, String key) {
        return OnboardingChecklistItem.builder().checklistId(checklistId).category(category).itemKey(key)
                .label(key).done(false).build();
    }

    private AssetAssignmentResponse assignment(String categoryName) {
        return AssetAssignmentResponse.builder().id(1L).assetId(1L).assetTag("TAG-1")
                .categoryName(categoryName).employeeUserId(employeeId).effectiveFrom(Instant.now()).build();
    }

    // ── startOnboarding: end-to-end creation, including its internal getDetail() call ──────

    @Test
    void startOnboarding_eligibleEmployee_createsChecklistAndReturnsDetail() {
        when(employeeRepo.existsById(employeeId)).thenReturn(true);
        when(checklistRepo.existsByEmployeeUserId(employeeId)).thenReturn(false);
        when(checklistRepo.save(any(OnboardingChecklist.class))).thenAnswer(inv -> {
            OnboardingChecklist c = inv.getArgument(0);
            if (c.getId() == null) c.setId(checklistId);
            return c;
        });
        when(checklistRepo.findById(checklistId)).thenAnswer(inv -> Optional.of(
                OnboardingChecklist.builder().id(checklistId).employeeUserId(employeeId)
                        .startedBy(adminId).status("IN_PROGRESS").startedAt(Instant.now()).build()));
        // Capture the 6 seeded items startOnboarding() constructs so the internal getDetail()
        // call — which re-reads via itemRepo, not the saveAll argument — sees them too, exactly
        // as a real repository backed by a real DB would.
        when(itemRepo.saveAll(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<OnboardingChecklistItem> items = (List<OnboardingChecklistItem>) inv.getArgument(0);
            when(itemRepo.findByChecklistIdOrderByDueDateAsc(checklistId)).thenReturn(items);
            return items;
        });
        when(assetService.currentAssignmentsForEmployee(employeeId)).thenReturn(List.of());
        when(documentService.requiredDocumentsFor(employeeId)).thenReturn(List.of());

        StartOnboardingRequest req = new StartOnboardingRequest();
        req.setEmployeeUserId(employeeId);

        OnboardingChecklistDetailDto detail = onboardingService.startOnboarding(req, ADMIN_EMAIL);

        assertEquals(checklistId, detail.getChecklistId());
        assertEquals(employeeId, detail.getEmployeeUserId());
        assertFalse(detail.isArchived());
        assertFalse(detail.isReadyToComplete());
        // 4 pre-boarding + (2 manual setup + 2 auto asset items) + 1 documents item
        assertEquals(9, detail.getTotalItems());
        assertEquals(0, detail.getDoneItems());
        verify(itemRepo).saveAll(argThat(items -> ((List<?>) items).size() == 6));
        verify(auditService).log(adminId, "ONBOARDING_STARTED", employeeId);
    }

    @Test
    void startOnboarding_employeeNotFound_throws() {
        UUID unknownId = UUID.randomUUID();
        when(employeeRepo.existsById(unknownId)).thenReturn(false);
        StartOnboardingRequest req = new StartOnboardingRequest();
        req.setEmployeeUserId(unknownId);

        assertThrows(NoSuchElementException.class, () -> onboardingService.startOnboarding(req, ADMIN_EMAIL));
        verify(checklistRepo, never()).save(any());
    }

    @Test
    void startOnboarding_alreadyStarted_throwsAndDoesNotDuplicate() {
        when(employeeRepo.existsById(employeeId)).thenReturn(true);
        when(checklistRepo.existsByEmployeeUserId(employeeId)).thenReturn(true);
        StartOnboardingRequest req = new StartOnboardingRequest();
        req.setEmployeeUserId(employeeId);

        assertThrows(IllegalStateException.class, () -> onboardingService.startOnboarding(req, ADMIN_EMAIL));
        verify(checklistRepo, never()).save(any());
    }

    // ── getDetail no longer auto-completes on read ──────────────────────────

    @Test
    void getDetail_allItemsDone_doesNotAutoCompleteButFlagsReadyToComplete() {
        when(checklistRepo.findById(checklistId)).thenReturn(Optional.of(inProgressChecklist()));
        stubAllItemsDone();

        OnboardingChecklistDetailDto detail = onboardingService.getDetail(checklistId, ADMIN_EMAIL);

        assertFalse(detail.isArchived());
        assertEquals("ON_TRACK", detail.getStatus());
        assertTrue(detail.isReadyToComplete());
        assertEquals(detail.getTotalItems(), detail.getDoneItems());
        verify(checklistRepo, never()).save(any());
    }

    @Test
    void getDetail_itemsPending_notReadyToComplete() {
        when(checklistRepo.findById(checklistId)).thenReturn(Optional.of(inProgressChecklist()));
        stubOneItemPending();

        OnboardingChecklistDetailDto detail = onboardingService.getDetail(checklistId, ADMIN_EMAIL);

        assertFalse(detail.isReadyToComplete());
        assertFalse(detail.isArchived());
        verify(checklistRepo, never()).save(any());
    }

    @Test
    void listQueue_doesNotMutateAnyChecklist() {
        OnboardingChecklist checklist = inProgressChecklist();
        when(checklistRepo.findAllWithActiveEmployee()).thenReturn(List.of(checklist));
        stubAllItemsDone();

        List<OnboardingChecklistSummaryDto> queue = onboardingService.listQueue(ADMIN_EMAIL);

        assertEquals(1, queue.size());
        assertFalse(queue.get(0).isArchived());
        verify(checklistRepo, never()).save(any());
    }

    // ── Explicit completion ──────────────────────────────────────────────────

    @Test
    void completeOnboarding_allItemsDone_transitionsToCompleted() {
        OnboardingChecklist inProgress = inProgressChecklist();
        OnboardingChecklist completed = inProgressChecklist();
        completed.setStatus("COMPLETED");
        completed.setCompletedAt(Instant.now());
        // First read (the pre-flight IN_PROGRESS/ready check) sees the pre-completion row; the
        // second read (inside the nested getDetail() call) sees what completeIfInProgress just
        // wrote — mirroring clearAutomatically forcing a fresh read from a real database.
        when(checklistRepo.findById(checklistId))
                .thenReturn(Optional.of(inProgress))
                .thenReturn(Optional.of(completed));
        when(checklistRepo.completeIfInProgress(eq(checklistId), any(Instant.class))).thenReturn(1);
        stubAllItemsDone();

        OnboardingChecklistDetailDto detail = onboardingService.completeOnboarding(checklistId, ADMIN_EMAIL);

        assertTrue(detail.isArchived());
        assertEquals("COMPLETE", detail.getStatus());
        verify(checklistRepo).completeIfInProgress(eq(checklistId), any(Instant.class));
        verify(auditService).log(adminId, "ONBOARDING_COMPLETED", employeeId);
    }

    @Test
    void completeOnboarding_itemsStillPending_throwsAndDoesNotTransition() {
        OnboardingChecklist checklist = inProgressChecklist();
        when(checklistRepo.findById(checklistId)).thenReturn(Optional.of(checklist));
        stubOneItemPending();

        assertThrows(IllegalStateException.class, () -> onboardingService.completeOnboarding(checklistId, ADMIN_EMAIL));

        assertEquals("IN_PROGRESS", checklist.getStatus());
        verify(checklistRepo, never()).completeIfInProgress(any(), any());
        verify(auditService, never()).log(any(), eq("ONBOARDING_COMPLETED"), any());
    }

    @Test
    void completeOnboarding_alreadyCompleted_throws() {
        OnboardingChecklist checklist = inProgressChecklist();
        checklist.setStatus("COMPLETED");
        checklist.setCompletedAt(Instant.now());
        when(checklistRepo.findById(checklistId)).thenReturn(Optional.of(checklist));

        assertThrows(IllegalStateException.class, () -> onboardingService.completeOnboarding(checklistId, ADMIN_EMAIL));
        verify(checklistRepo, never()).completeIfInProgress(any(), any());
    }

    // ── Concurrent completion: two requests racing to complete the same checklist ───────────

    @Test
    void completeOnboarding_lostRaceToConcurrentCompletion_rejectsWithoutDuplicateAuditEffects() {
        // Both requests read the checklist while it's still IN_PROGRESS and both see all items
        // done — but only one of them can win the atomic conditional update at the database.
        // This simulates the loser: completeIfInProgress's WHERE status='IN_PROGRESS' no longer
        // matches because the winner's request already flipped it, so it reports 0 rows updated.
        OnboardingChecklist checklist = inProgressChecklist();
        when(checklistRepo.findById(checklistId)).thenReturn(Optional.of(checklist));
        stubAllItemsDone();
        when(checklistRepo.completeIfInProgress(eq(checklistId), any(Instant.class))).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> onboardingService.completeOnboarding(checklistId, ADMIN_EMAIL));

        // The loser must produce no completion side effects at all — no second COMPLETED write
        // (already guaranteed by the atomic update itself) and, crucially, no second audit entry.
        verify(auditService, never()).log(any(), eq("ONBOARDING_COMPLETED"), any());
    }

    @Test
    void completeOnboarding_winsRace_completesExactlyOnce() {
        OnboardingChecklist inProgress = inProgressChecklist();
        OnboardingChecklist completed = inProgressChecklist();
        completed.setStatus("COMPLETED");
        completed.setCompletedAt(Instant.now());
        when(checklistRepo.findById(checklistId))
                .thenReturn(Optional.of(inProgress))
                .thenReturn(Optional.of(completed));
        stubAllItemsDone();
        when(checklistRepo.completeIfInProgress(eq(checklistId), any(Instant.class))).thenReturn(1);

        onboardingService.completeOnboarding(checklistId, ADMIN_EMAIL);

        verify(checklistRepo, times(1)).completeIfInProgress(eq(checklistId), any(Instant.class));
        verify(auditService, times(1)).log(adminId, "ONBOARDING_COMPLETED", employeeId);
    }

    // ── eligibleEmployees: preserve existing exclusion behavior ─────────────

    @Test
    void eligibleEmployees_excludesEmployeesWithAnyChecklistRegardlessOfStatus() {
        UUID pendingEmployeeId = UUID.randomUUID();
        UUID startedEmployeeId = UUID.randomUUID();
        UUID completedEmployeeId = UUID.randomUUID();

        when(checklistRepo.findAll()).thenReturn(List.of(
                OnboardingChecklist.builder().id(UUID.randomUUID()).employeeUserId(startedEmployeeId).status("IN_PROGRESS").build(),
                OnboardingChecklist.builder().id(UUID.randomUUID()).employeeUserId(completedEmployeeId).status("COMPLETED").build()
        ));
        when(employeeService.listEmployees()).thenReturn(List.of(
                EmployeeResponse.builder().userId(pendingEmployeeId).fullName("Pending Employee").build(),
                EmployeeResponse.builder().userId(startedEmployeeId).fullName("Started Employee").build(),
                EmployeeResponse.builder().userId(completedEmployeeId).fullName("Completed Employee").build()
        ));

        List<EmployeeResponse> eligible = onboardingService.eligibleEmployees(ADMIN_EMAIL);

        assertEquals(1, eligible.size());
        assertEquals(pendingEmployeeId, eligible.get(0).getUserId());
    }

    // ── toggleItem still blocks edits once completed ────────────────────────

    @Test
    void toggleItem_onCompletedChecklist_stillRejected() {
        OnboardingChecklist checklist = inProgressChecklist();
        checklist.setStatus("COMPLETED");
        UUID itemId = UUID.randomUUID();
        when(checklistRepo.findById(checklistId)).thenReturn(Optional.of(checklist));
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(
                OnboardingChecklistItem.builder().id(itemId).checklistId(checklistId).category("PRE_BOARDING")
                        .itemKey("X").label("X").done(false).build()));

        assertThrows(IllegalStateException.class, () -> onboardingService.toggleItem(checklistId, itemId, ADMIN_EMAIL));
    }
}
