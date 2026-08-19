package com.nforce.onehr.service;

import com.nforce.onehr.dto.helpcontent.*;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests — same isolation approach as {@code HelpdeskServiceTest}. Specification
 * based filtering (listPublished/listAll) is thin Spring Data plumbing not meaningfully testable
 * against a mock repository, so these focus on authorization guards, the six-status lifecycle
 * transitions, approver resolution, and the approval-attempt audit trail — the module's actual
 * logic.
 */
@ExtendWith(MockitoExtension.class)
class HelpContentServiceTest {

    @Mock private HelpContentRepository repo;
    @Mock private HelpContentAttachmentRepository attachmentRepo;
    @Mock private HelpContentApprovalRepository approvalRepo;
    @Mock private HelpContentApprovalAttachmentRepository approvalAttachmentRepo;
    @Mock private EmployeeManagerHistoryRepository managerHistoryRepo;
    @Mock private NotificationService notificationService;
    @Mock private UserRepository userRepo;
    @Mock private EmployeeRepository employeeRepo;

    @InjectMocks private HelpContentService service;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID hrAdminId = UUID.randomUUID();
    private final String employeeEmail = "employee@test.com";
    private final String hrAdminEmail = "hr@test.com";

    private User employeeUser;
    private User hrAdminUser;

    @BeforeEach
    void setUp() {
        employeeUser = User.builder().id(employeeId).email(employeeEmail).active(true)
                .roles(Set.of(Role.builder().code("EMPLOYEE").build())).build();
        hrAdminUser = User.builder().id(hrAdminId).email(hrAdminEmail).active(true)
                .roles(Set.of(Role.builder().code("HR_ADMIN").build())).build();

        lenient().when(employeeRepo.findById(any())).thenReturn(Optional.empty());
        lenient().when(userRepo.findById(employeeId)).thenReturn(Optional.of(employeeUser));
        lenient().when(userRepo.findById(hrAdminId)).thenReturn(Optional.of(hrAdminUser));
        lenient().when(repo.save(any(HelpContent.class))).thenAnswer(inv -> {
            HelpContent c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });
        lenient().when(attachmentRepo.save(any(HelpContentAttachment.class))).thenAnswer(inv -> {
            HelpContentAttachment a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            return a;
        });
        lenient().when(approvalRepo.save(any(HelpContentApproval.class))).thenAnswer(inv -> {
            HelpContentApproval a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            return a;
        });
    }

    private HelpContent faq() {
        return HelpContent.builder().id(UUID.randomUUID()).type(HelpContentType.FAQ.name())
                .title("How do I apply for leave?").createdBy(hrAdminId).build(); // status defaults to DRAFT
    }

    private static String sha256(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Creation (role-gated, starts DRAFT) ─────────────────────

    @Test
    void create_asEmployee_isDenied() {
        when(userRepo.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        CreateHelpContentRequest req = new CreateHelpContentRequest();
        req.setType("FAQ");
        req.setTitle("x");

        assertThrows(AccessDeniedException.class, () -> service.create(req, employeeEmail));
        verify(repo, never()).save(any());
    }

    @Test
    void create_asHrAdmin_startsAsDraft() {
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        CreateHelpContentRequest req = new CreateHelpContentRequest();
        req.setType("GUIDE");
        req.setTitle("Leave Policy");

        HelpContentDetailDto detail = service.create(req, hrAdminEmail);

        assertEquals("GUIDE", detail.getType());
        assertEquals("DRAFT", detail.getStatus());
    }

    @Test
    void create_withInvalidType_isRejected() {
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        CreateHelpContentRequest req = new CreateHelpContentRequest();
        req.setType("NOT_A_TYPE");
        req.setTitle("x");

        assertThrows(IllegalArgumentException.class, () -> service.create(req, hrAdminEmail));
        verify(repo, never()).save(any());
    }

    // ── Submission & approver resolution ────────────────────────

    @Test
    void submit_fromDraft_resolvesDirectActiveManagerAndCreatesFirstAttempt() {
        HelpContent content = faq();
        UUID managerId = UUID.randomUUID();
        User manager = User.builder().id(managerId).email("manager@test.com").active(true)
                .roles(Set.of(Role.builder().code("MANAGER").build())).build();

        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));
        when(attachmentRepo.findByContentIdOrderByDisplayOrderAsc(content.getId())).thenReturn(List.of());
        when(approvalRepo.countByContentId(content.getId())).thenReturn(0L);
        when(managerHistoryRepo.findByEmployeeUserIdAndEffectiveToIsNull(hrAdminId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(hrAdminId).managerUserId(managerId).build()));
        when(userRepo.findById(managerId)).thenReturn(Optional.of(manager));

        HelpContentDetailDto result = service.submit(content.getId(), hrAdminEmail);

        assertEquals("PENDING_APPROVAL", result.getStatus());
        ArgumentCaptor<HelpContentApproval> captor = ArgumentCaptor.forClass(HelpContentApproval.class);
        verify(approvalRepo).save(captor.capture());
        assertEquals(managerId, captor.getValue().getApproverId());
        assertEquals(1, captor.getValue().getAttemptNumber());
        assertEquals("PENDING", captor.getValue().getStatus());
        verify(notificationService).send(eq(managerId), eq("HELP_CONTENT_SUBMITTED"), any(), any(), any());
    }

    @Test
    void submit_withInactiveDirectManager_walksUpToNextActiveManager() {
        HelpContent content = faq();
        UUID inactiveMgrId = UUID.randomUUID();
        UUID activeMgrId = UUID.randomUUID();
        User inactiveMgr = User.builder().id(inactiveMgrId).email("inactive@test.com").active(false).build();
        User activeMgr = User.builder().id(activeMgrId).email("active@test.com").active(true)
                .roles(Set.of(Role.builder().code("MANAGER").build())).build();

        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));
        when(attachmentRepo.findByContentIdOrderByDisplayOrderAsc(content.getId())).thenReturn(List.of());
        when(approvalRepo.countByContentId(content.getId())).thenReturn(0L);
        when(managerHistoryRepo.findByEmployeeUserIdAndEffectiveToIsNull(hrAdminId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(hrAdminId).managerUserId(inactiveMgrId).build()));
        when(managerHistoryRepo.findByEmployeeUserIdAndEffectiveToIsNull(inactiveMgrId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(inactiveMgrId).managerUserId(activeMgrId).build()));
        when(userRepo.findById(inactiveMgrId)).thenReturn(Optional.of(inactiveMgr));
        when(userRepo.findById(activeMgrId)).thenReturn(Optional.of(activeMgr));

        service.submit(content.getId(), hrAdminEmail);

        ArgumentCaptor<HelpContentApproval> captor = ArgumentCaptor.forClass(HelpContentApproval.class);
        verify(approvalRepo).save(captor.capture());
        assertEquals(activeMgrId, captor.getValue().getApproverId());
    }

    @Test
    void submit_withNoManagerInChain_fallsBackToActiveSuperAdmin() {
        HelpContent content = faq();
        UUID superAdminId = UUID.randomUUID();
        User superAdmin = User.builder().id(superAdminId).email("sa@test.com").active(true)
                .roles(Set.of(Role.builder().code("SUPER_ADMIN").build())).build();

        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));
        when(attachmentRepo.findByContentIdOrderByDisplayOrderAsc(content.getId())).thenReturn(List.of());
        when(approvalRepo.countByContentId(content.getId())).thenReturn(0L);
        when(managerHistoryRepo.findByEmployeeUserIdAndEffectiveToIsNull(hrAdminId)).thenReturn(Optional.empty());
        when(userRepo.findActiveSuperAdmins()).thenReturn(List.of(superAdmin));

        service.submit(content.getId(), hrAdminEmail);

        ArgumentCaptor<HelpContentApproval> captor = ArgumentCaptor.forClass(HelpContentApproval.class);
        verify(approvalRepo).save(captor.capture());
        assertEquals(superAdminId, captor.getValue().getApproverId());
    }

    @Test
    void submit_notFromDraft_isRejected() {
        HelpContent content = faq();
        content.setStatus("PENDING_APPROVAL");
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        assertThrows(AccessDeniedException.class, () -> service.submit(content.getId(), hrAdminEmail));
    }

    // ── Pending approval is locked ───────────────────────────────

    @Test
    void update_onPendingApprovalContent_isLocked() {
        HelpContent content = faq();
        content.setStatus("PENDING_APPROVAL");
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));
        UpdateHelpContentRequest req = new UpdateHelpContentRequest();
        req.setTitle("x");

        assertThrows(AccessDeniedException.class, () -> service.update(content.getId(), req, hrAdminEmail));
    }

    @Test
    void archive_onPendingApprovalContent_isRejected() {
        HelpContent content = faq();
        content.setStatus("PENDING_APPROVAL");
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        assertThrows(AccessDeniedException.class, () -> service.archive(content.getId(), hrAdminEmail));
    }

    @Test
    void delete_onPendingApprovalContent_isRejected() {
        HelpContent content = faq();
        content.setStatus("PENDING_APPROVAL");
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        assertThrows(AccessDeniedException.class, () -> service.delete(content.getId(), hrAdminEmail));
    }

    @Test
    void withdraw_fromPendingApproval_returnsToDraftAndStoresReason() {
        HelpContent content = faq();
        content.setStatus("PENDING_APPROVAL");
        HelpContentApproval attempt = HelpContentApproval.builder().id(UUID.randomUUID())
                .contentId(content.getId()).status("PENDING").attemptNumber(1).build();

        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));
        when(approvalRepo.findByContentIdAndStatus(content.getId(), "PENDING")).thenReturn(Optional.of(attempt));

        WithdrawRequest req = new WithdrawRequest();
        req.setReason("Need to fix a typo before HR review");

        HelpContentDetailDto result = service.withdraw(content.getId(), req, hrAdminEmail);

        assertEquals("DRAFT", result.getStatus());
        ArgumentCaptor<HelpContentApproval> captor = ArgumentCaptor.forClass(HelpContentApproval.class);
        verify(approvalRepo).save(captor.capture());
        assertEquals("WITHDRAWN", captor.getValue().getStatus());
        assertEquals("Need to fix a typo before HR review", captor.getValue().getWithdrawalReason());
    }

    // ── Reject / Approve ─────────────────────────────────────────

    @Test
    void reject_returnsContentToDraftAndStoresReasonForAuthor() {
        HelpContent content = faq();
        content.setStatus("PENDING_APPROVAL");
        HelpContentApproval attempt = HelpContentApproval.builder().id(UUID.randomUUID())
                .contentId(content.getId()).approverId(hrAdminId).status("PENDING").attemptNumber(1)
                .snapshotTitle(content.getTitle()).build();

        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(approvalRepo.findById(attempt.getId())).thenReturn(Optional.of(attempt));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        RejectRequest req = new RejectRequest();
        req.setReason("Please fix the formatting");

        HelpContentDetailDto result = service.rejectAttempt(attempt.getId(), req, hrAdminEmail);

        assertEquals("DRAFT", result.getStatus());
        assertEquals("Please fix the formatting", result.getRejectionReason());
    }

    @Test
    void approve_setsContentApprovedAndClearsRejectionReason() {
        HelpContent content = faq();
        content.setStatus("PENDING_APPROVAL");
        content.setRejectionReason("old reason from a prior attempt");
        HelpContentApproval attempt = HelpContentApproval.builder().id(UUID.randomUUID())
                .contentId(content.getId()).approverId(hrAdminId).status("PENDING").attemptNumber(2)
                .snapshotTitle(content.getTitle()).build();

        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(approvalRepo.findById(attempt.getId())).thenReturn(Optional.of(attempt));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        HelpContentDetailDto result = service.approveAttempt(attempt.getId(), hrAdminEmail);

        assertEquals("APPROVED", result.getStatus());
        assertNull(result.getRejectionReason());
    }

    @Test
    void approve_byUnrelatedUser_isDenied() {
        HelpContentApproval attempt = HelpContentApproval.builder().id(UUID.randomUUID())
                .contentId(UUID.randomUUID()).approverId(UUID.randomUUID()).status("PENDING").build();
        when(userRepo.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(approvalRepo.findById(attempt.getId())).thenReturn(Optional.of(attempt));

        assertThrows(AccessDeniedException.class, () -> service.approveAttempt(attempt.getId(), employeeEmail));
    }

    @Test
    void approve_bySuperAdmin_isAllowedAsFallbackAuthorityEvenIfNotTheResolvedApprover() {
        UUID superAdminId = UUID.randomUUID();
        String superAdminEmail = "sa2@test.com";
        User superAdminUser = User.builder().id(superAdminId).email(superAdminEmail)
                .roles(Set.of(Role.builder().code("SUPER_ADMIN").build())).build();
        HelpContent content = faq();
        content.setStatus("PENDING_APPROVAL");
        HelpContentApproval attempt = HelpContentApproval.builder().id(UUID.randomUUID())
                .contentId(content.getId()).approverId(UUID.randomUUID()).status("PENDING").attemptNumber(1)
                .snapshotTitle(content.getTitle()).build();

        when(userRepo.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminUser));
        when(approvalRepo.findById(attempt.getId())).thenReturn(Optional.of(attempt));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        HelpContentDetailDto result = service.approveAttempt(attempt.getId(), superAdminEmail);

        assertEquals("APPROVED", result.getStatus());
    }

    @Test
    void approve_byTheAuthorWhoSubmittedIt_isDeniedEvenIfSuperAdmin() {
        HelpContent content = faq();
        content.setStatus("PENDING_APPROVAL");
        content.setCreatedBy(hrAdminId);
        HelpContentApproval attempt = HelpContentApproval.builder().id(UUID.randomUUID())
                .contentId(content.getId()).approverId(hrAdminId).submittedBy(hrAdminId).status("PENDING").attemptNumber(1)
                .snapshotTitle(content.getTitle()).build();

        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(approvalRepo.findById(attempt.getId())).thenReturn(Optional.of(attempt));

        assertThrows(AccessDeniedException.class, () -> service.approveAttempt(attempt.getId(), hrAdminEmail));
    }

    @Test
    void withdraw_byNonAuthor_isDenied() {
        HelpContent content = faq(); // createdBy = hrAdminId
        content.setStatus("PENDING_APPROVAL");
        UUID otherAdminId = UUID.randomUUID();
        String otherAdminEmail = "other-hr@test.com";
        User otherAdmin = User.builder().id(otherAdminId).email(otherAdminEmail).active(true)
                .roles(Set.of(Role.builder().code("HR_ADMIN").build())).build();

        when(userRepo.findByEmail(otherAdminEmail)).thenReturn(Optional.of(otherAdmin));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        WithdrawRequest req = new WithdrawRequest();
        req.setReason("not my request");

        assertThrows(AccessDeniedException.class, () -> service.withdraw(content.getId(), req, otherAdminEmail));
    }

    @Test
    void resubmission_afterRejection_createsANewAttemptNumber() {
        HelpContent content = faq();
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));
        when(attachmentRepo.findByContentIdOrderByDisplayOrderAsc(content.getId())).thenReturn(List.of());
        when(managerHistoryRepo.findByEmployeeUserIdAndEffectiveToIsNull(hrAdminId)).thenReturn(Optional.empty());
        when(userRepo.findActiveSuperAdmins()).thenReturn(List.of(hrAdminUser));

        when(approvalRepo.countByContentId(content.getId())).thenReturn(0L);
        service.submit(content.getId(), hrAdminEmail); // attempt #1

        HelpContentApproval attempt1 = HelpContentApproval.builder().id(UUID.randomUUID())
                .contentId(content.getId()).approverId(hrAdminId).status("PENDING").attemptNumber(1)
                .snapshotTitle(content.getTitle()).build();
        when(approvalRepo.findById(attempt1.getId())).thenReturn(Optional.of(attempt1));
        RejectRequest rejectReq = new RejectRequest();
        rejectReq.setReason("needs work");
        service.rejectAttempt(attempt1.getId(), rejectReq, hrAdminEmail); // -> DRAFT

        when(approvalRepo.countByContentId(content.getId())).thenReturn(1L);
        service.submit(content.getId(), hrAdminEmail); // attempt #2

        // 3 saves total: attempt #1 created by submit(), attempt #1 updated to REJECTED by
        // rejectAttempt(), then attempt #2 created by the second submit().
        ArgumentCaptor<HelpContentApproval> captor = ArgumentCaptor.forClass(HelpContentApproval.class);
        verify(approvalRepo, times(3)).save(captor.capture());
        assertEquals(1, captor.getAllValues().get(0).getAttemptNumber());
        assertEquals(2, captor.getAllValues().get(2).getAttemptNumber());
    }

    // ── Publish / Unpublish / Archive / Restore ──────────────────

    @Test
    void publish_fromDraft_isRejected() {
        HelpContent content = faq();
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        assertThrows(AccessDeniedException.class, () -> service.publish(content.getId(), hrAdminEmail));
    }

    @Test
    void publish_fromApproved_succeeds() {
        HelpContent content = faq();
        content.setStatus("APPROVED");
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        HelpContentDetailDto result = service.publish(content.getId(), hrAdminEmail);

        assertEquals("PUBLISHED", result.getStatus());
    }

    @Test
    void unpublish_thenPublish_requiresNoNewApproval() {
        HelpContent content = faq();
        content.setStatus("PUBLISHED");
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        HelpContentDetailDto unpublished = service.unpublish(content.getId(), hrAdminEmail);
        assertEquals("UNPUBLISHED", unpublished.getStatus());

        HelpContentDetailDto republished = service.publish(content.getId(), hrAdminEmail);
        assertEquals("PUBLISHED", republished.getStatus());
        verifyNoInteractions(approvalRepo);
    }

    @Test
    void editingUnpublishedContent_demotesToDraftRequiringApprovalAgain() {
        HelpContent content = faq();
        content.setStatus("UNPUBLISHED");
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        UpdateHelpContentRequest req = new UpdateHelpContentRequest();
        req.setTitle("Updated while unpublished");

        HelpContentDetailDto result = service.update(content.getId(), req, hrAdminEmail);

        assertEquals("DRAFT", result.getStatus());
        assertEquals(content.getId(), result.getId()); // in-place, no fork — nothing was employee-visible
        assertThrows(AccessDeniedException.class, () -> service.publish(content.getId(), hrAdminEmail));
    }

    @Test
    void editingApprovedContent_demotesToDraftInPlace() {
        HelpContent content = faq();
        content.setStatus("APPROVED");
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        UpdateHelpContentRequest req = new UpdateHelpContentRequest();
        req.setTitle("Corrected wording");

        HelpContentDetailDto result = service.update(content.getId(), req, hrAdminEmail);

        assertEquals("DRAFT", result.getStatus());
        assertEquals(content.getId(), result.getId());
    }

    @Test
    void editingPublishedContent_forksNewDraftRevisionAndLeavesOriginalLive() {
        HelpContent published = faq();
        published.setStatus("PUBLISHED");
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(published.getId())).thenReturn(Optional.of(published));
        when(attachmentRepo.findByContentIdOrderByDisplayOrderAsc(published.getId())).thenReturn(List.of());

        UpdateHelpContentRequest req = new UpdateHelpContentRequest();
        req.setTitle("Updated wording for v2");

        HelpContentDetailDto revision = service.update(published.getId(), req, hrAdminEmail);

        assertNotEquals(published.getId(), revision.getId());
        assertEquals("DRAFT", revision.getStatus());
        assertEquals("PUBLISHED", published.getStatus()); // employees still see the old version
    }

    @Test
    void publishingAForkedRevision_archivesTheSupersededOriginal() {
        HelpContent original = faq();
        original.setStatus("PUBLISHED");
        HelpContent revision = HelpContent.builder().id(UUID.randomUUID()).type("FAQ").title("v2")
                .status("APPROVED").supersedesId(original.getId()).createdBy(hrAdminId).build();

        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(revision.getId())).thenReturn(Optional.of(revision));
        when(repo.findById(original.getId())).thenReturn(Optional.of(original));

        HelpContentDetailDto result = service.publish(revision.getId(), hrAdminEmail);

        assertEquals("PUBLISHED", result.getStatus());
        assertEquals("ARCHIVED", original.getStatus());
    }

    @Test
    void archive_removesContentFromEmployeeVisibility() {
        HelpContent content = faq();
        content.setStatus("PUBLISHED");
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        service.archive(content.getId(), hrAdminEmail);

        assertThrows(NoSuchElementException.class, () -> service.getPublished(content.getId()));
    }

    @Test
    void restore_fromArchived_alwaysReturnsToDraftNeverPublished() {
        HelpContent content = faq();
        content.setStatus("ARCHIVED");
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        HelpContentDetailDto result = service.restore(content.getId(), hrAdminEmail);

        assertEquals("DRAFT", result.getStatus());
    }

    @Test
    void restore_fromNonArchivedStatus_isRejected() {
        HelpContent content = faq();
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        assertThrows(AccessDeniedException.class, () -> service.restore(content.getId(), hrAdminEmail));
    }

    // ── Archive / Delete availability per status ───────────────────
    //
    // Business rule: an unapproved DRAFT has nothing worth retaining via archive (delete only);
    // everything except the locked PENDING_APPROVAL stage may be permanently deleted by HR/Super
    // Admin.

    @Test
    void delete_fromDraft_isAllowed() {
        HelpContent content = faq();
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        service.delete(content.getId(), hrAdminEmail);

        verify(repo).delete(content);
    }

    @Test
    void delete_fromPublished_isAllowed() {
        HelpContent content = faq();
        content.setStatus("PUBLISHED");
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        service.delete(content.getId(), hrAdminEmail);

        verify(repo).delete(content);
    }

    @Test
    void delete_fromApproved_isAllowed() {
        HelpContent content = faq();
        content.setStatus("APPROVED");
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        service.delete(content.getId(), hrAdminEmail);

        verify(repo).delete(content);
    }

    @Test
    void delete_fromUnpublished_isAllowed() {
        HelpContent content = faq();
        content.setStatus("UNPUBLISHED");
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        service.delete(content.getId(), hrAdminEmail);

        verify(repo).delete(content);
    }

    @Test
    void archive_fromDraft_isRejected() {
        HelpContent content = faq(); // status defaults to DRAFT
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        assertThrows(AccessDeniedException.class, () -> service.archive(content.getId(), hrAdminEmail));
    }

    @Test
    void archive_fromApproved_isAllowed() {
        HelpContent content = faq();
        content.setStatus("APPROVED");
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        HelpContentDetailDto result = service.archive(content.getId(), hrAdminEmail);

        assertEquals("ARCHIVED", result.getStatus());
    }

    // ── Employee visibility ───────────────────────────────────────

    @Test
    void getPublished_onApprovedButUnpublishedContent_isInvisibleToEmployees() {
        HelpContent content = faq();
        content.setStatus("APPROVED");
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        assertThrows(NoSuchElementException.class, () -> service.getPublished(content.getId()));
    }

    @Test
    void getPublished_onPublishedContent_isVisible() {
        HelpContent content = faq();
        content.setStatus("PUBLISHED");
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        HelpContentDetailDto detail = service.getPublished(content.getId());

        assertEquals(content.getTitle(), detail.getTitle());
    }

    // ── Attachments participate in approval ───────────────────────

    @Test
    void addAttachment_onApprovedContent_demotesToDraftRequiringNewApproval() throws IOException {
        HelpContent content = faq();
        content.setStatus("APPROVED");
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));
        when(attachmentRepo.findByContentIdOrderByDisplayOrderAsc(content.getId())).thenReturn(List.of());

        MultipartFile file = new MockMultipartFile("file", "policy.pdf", "application/pdf", "hello".getBytes());
        HelpContentDetailDto result = service.addAttachment(content.getId(), file, hrAdminEmail);

        assertEquals("DRAFT", result.getStatus());
        verify(attachmentRepo).save(any(HelpContentAttachment.class));
    }

    @Test
    void removeAttachment_onApprovedContent_demotesToDraftRequiringNewApproval() {
        HelpContent content = faq();
        content.setStatus("APPROVED");
        HelpContentAttachment attachment = HelpContentAttachment.builder().id(UUID.randomUUID())
                .contentId(content.getId()).fileName("policy.pdf").checksum("abc").build();

        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));
        when(attachmentRepo.findById(attachment.getId())).thenReturn(Optional.of(attachment));

        HelpContentDetailDto result = service.removeAttachment(content.getId(), attachment.getId(), hrAdminEmail);

        assertEquals("DRAFT", result.getStatus());
        verify(attachmentRepo).delete(attachment);
    }

    @Test
    void removeThenReAddSameAttachment_stillRequiresAFreshApprovalCycle() throws IOException {
        HelpContent content = faq(); // DRAFT
        byte[] bytes = "same-bytes".getBytes();
        HelpContentAttachment existing = HelpContentAttachment.builder().id(UUID.randomUUID())
                .contentId(content.getId()).displayOrder(0).fileName("policy.pdf").fileType("application/pdf")
                .fileSize((long) bytes.length).fileData(bytes).checksum(sha256(bytes)).build();

        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));
        when(attachmentRepo.findById(existing.getId())).thenReturn(Optional.of(existing));

        // Remove, then re-add the exact same file — the content itself never left DRAFT, so it
        // must still be explicitly submitted; the point is approval is never bypassed just
        // because the final attachment state happens to match what it was before.
        service.removeAttachment(content.getId(), existing.getId(), hrAdminEmail);
        MultipartFile file = new MockMultipartFile("file", "policy.pdf", "application/pdf", bytes);
        service.addAttachment(content.getId(), file, hrAdminEmail);

        when(attachmentRepo.findByContentIdOrderByDisplayOrderAsc(content.getId())).thenReturn(List.of());
        when(approvalRepo.countByContentId(content.getId())).thenReturn(0L);
        when(managerHistoryRepo.findByEmployeeUserIdAndEffectiveToIsNull(hrAdminId)).thenReturn(Optional.empty());
        when(userRepo.findActiveSuperAdmins()).thenReturn(List.of(hrAdminUser));

        assertEquals("DRAFT", content.getStatus());
        service.submit(content.getId(), hrAdminEmail);
        assertEquals("PENDING_APPROVAL", content.getStatus());
    }

    /**
     * Regression: found via live E2E testing (add A/B/C, reorder, remove A leaving a gap at
     * order 1, then re-add) — using COUNT instead of MAX(order)+1 for the new attachment's
     * position collided with a surviving attachment's order, which then made
     * remapAttachment's order-based tie-breaking pick the wrong attachment on a later fork.
     */
    @Test
    void addAttachment_afterAGapLeftByEarlierRemoval_doesNotCollideWithSurvivingOrder() throws IOException {
        HelpContent content = faq(); // DRAFT
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        // Simulates the repository state after 3 attachments were added (orders 0,1,2) and the
        // middle one (order 1) was removed — orders 0 and 2 survive, leaving a gap.
        HelpContentAttachment survivorAtOrder0 = HelpContentAttachment.builder().id(UUID.randomUUID())
                .contentId(content.getId()).displayOrder(0).fileName("a.txt").checksum("a").build();
        HelpContentAttachment survivorAtOrder2 = HelpContentAttachment.builder().id(UUID.randomUUID())
                .contentId(content.getId()).displayOrder(2).fileName("c.txt").checksum("c").build();
        when(attachmentRepo.findByContentIdOrderByDisplayOrderAsc(content.getId()))
                .thenReturn(List.of(survivorAtOrder0, survivorAtOrder2));

        MultipartFile file = new MockMultipartFile("file", "d.txt", "text/plain", "d".getBytes());
        service.addAttachment(content.getId(), file, hrAdminEmail);

        ArgumentCaptor<HelpContentAttachment> captor = ArgumentCaptor.forClass(HelpContentAttachment.class);
        verify(attachmentRepo).save(captor.capture());
        assertEquals(3, captor.getValue().getDisplayOrder(), "must be MAX(order)+1 = 3, not COUNT = 2 (which collides with the order-2 survivor)");
    }

    @Test
    void addAttachment_withUnsupportedFileType_isRejected() {
        HelpContent content = faq();
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        MultipartFile file = new MockMultipartFile("file", "script.exe", "application/octet-stream", "x".getBytes());

        assertThrows(IllegalArgumentException.class, () -> service.addAttachment(content.getId(), file, hrAdminEmail));
        verify(attachmentRepo, never()).save(any());
    }

    @Test
    void addAttachment_exceedingSizeLimit_isRejected() {
        HelpContent content = faq();
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(11L * 1024 * 1024);
        when(file.getOriginalFilename()).thenReturn("big.pdf");

        assertThrows(IllegalArgumentException.class, () -> service.addAttachment(content.getId(), file, hrAdminEmail));
        verify(attachmentRepo, never()).save(any());
    }

    @Test
    void addAttachment_pastTheAttachmentLimit_isRejected() {
        HelpContent content = faq();
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));
        when(attachmentRepo.countByContentId(content.getId())).thenReturn(5L);

        MultipartFile file = new MockMultipartFile("file", "f.pdf", "application/pdf", "x".getBytes());

        assertThrows(IllegalArgumentException.class, () -> service.addAttachment(content.getId(), file, hrAdminEmail));
        verify(attachmentRepo, never()).save(any());
    }

    @Test
    void addAttachments_multipleFilesInOneCall_savesAllWithSequentialOrder() throws IOException {
        HelpContent content = faq();
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));
        when(attachmentRepo.findByContentIdOrderByDisplayOrderAsc(content.getId())).thenReturn(List.of());
        when(attachmentRepo.countByContentId(content.getId())).thenReturn(0L);

        List<MultipartFile> files = List.of(
                new MockMultipartFile("files", "a.pdf", "application/pdf", "a".getBytes()),
                new MockMultipartFile("files", "b.png", "image/png", "b".getBytes()));

        service.addAttachments(content.getId(), files, hrAdminEmail);

        ArgumentCaptor<HelpContentAttachment> captor = ArgumentCaptor.forClass(HelpContentAttachment.class);
        verify(attachmentRepo, times(2)).save(captor.capture());
        assertEquals(0, captor.getAllValues().get(0).getDisplayOrder());
        assertEquals(1, captor.getAllValues().get(1).getDisplayOrder());
    }

    @Test
    void addAttachments_exceedingLimitAsABatch_isRejectedBeforeSavingAny() {
        HelpContent content = faq();
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));
        when(attachmentRepo.countByContentId(content.getId())).thenReturn(3L);

        List<MultipartFile> files = List.of(
                new MockMultipartFile("files", "a.pdf", "application/pdf", "a".getBytes()),
                new MockMultipartFile("files", "b.png", "image/png", "b".getBytes()),
                new MockMultipartFile("files", "c.png", "image/png", "c".getBytes()));

        assertThrows(IllegalArgumentException.class, () -> service.addAttachments(content.getId(), files, hrAdminEmail));
        verify(attachmentRepo, never()).save(any());
    }

    // ── Approval Center pending-only queue ────────────────────────

    @Test
    void listPendingApprovals_forManager_returnsOnlyAttemptsResolvedToThem() {
        HelpContentApproval attempt = HelpContentApproval.builder().id(UUID.randomUUID())
                .contentId(UUID.randomUUID()).approverId(hrAdminId).status("PENDING").attemptNumber(1)
                .submittedBy(hrAdminId).snapshotTitle("Leave FAQ").build();
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(approvalRepo.findByApproverIdAndStatus(hrAdminId, "PENDING")).thenReturn(List.of(attempt));

        List<ApprovalAttemptDto> result = service.listPendingApprovalsForApprover(hrAdminEmail);

        assertEquals(1, result.size());
        assertEquals("Leave FAQ", result.get(0).getContentTitle());
        verify(approvalRepo, never()).findByStatus(any());
    }

    @Test
    void listPendingApprovals_forSuperAdmin_seesEveryPendingAttemptRegardlessOfApprover() {
        UUID saId = UUID.randomUUID();
        String saEmail = "sa3@test.com";
        User sa = User.builder().id(saId).email(saEmail)
                .roles(Set.of(Role.builder().code("SUPER_ADMIN").build())).build();
        HelpContentApproval attempt = HelpContentApproval.builder().id(UUID.randomUUID())
                .contentId(UUID.randomUUID()).approverId(UUID.randomUUID()).status("PENDING").attemptNumber(1)
                .submittedBy(UUID.randomUUID()).snapshotTitle("Guide").build();

        when(userRepo.findByEmail(saEmail)).thenReturn(Optional.of(sa));
        when(approvalRepo.findByStatus("PENDING")).thenReturn(List.of(attempt));

        List<ApprovalAttemptDto> result = service.listPendingApprovalsForApprover(saEmail);

        assertEquals(1, result.size());
        verify(approvalRepo, never()).findByApproverIdAndStatus(any(), any());
    }
}
