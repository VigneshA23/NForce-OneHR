package com.nforce.onehr.service;

import com.nforce.onehr.dto.doc.EmployeeDocumentResponse;
import com.nforce.onehr.entity.DocumentType;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeDocument;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.DocumentTypeRepository;
import com.nforce.onehr.repository.EmployeeDocumentRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.NotificationRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Retain Document History on Re-upload — DocumentService#uploadDocument must never overwrite the
 * previous EmployeeDocument row in place; each submission becomes its own version, and the
 * previous version's status/rejection reason/file stay untouched.
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock private EmployeeDocumentRepository docRepo;
    @Mock private DocumentTypeRepository docTypeRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private UserRepository userRepo;
    @Mock private NotificationRepository notificationRepo;
    @Mock private NotificationService notificationService;

    private DocumentService documentService;

    private static final String EMPLOYEE_EMAIL = "employee@test.com";
    private UUID employeeId;
    private DocumentType docType;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(docRepo, docTypeRepo, employeeRepo, userRepo, notificationRepo, notificationService);

        employeeId = UUID.randomUUID();
        User employee = User.builder().id(employeeId).email(EMPLOYEE_EMAIL)
                .roles(new HashSet<>(Set.of(role("EMPLOYEE")))).build();
        lenient().when(userRepo.findByEmail(EMPLOYEE_EMAIL)).thenReturn(Optional.of(employee));

        docType = DocumentType.builder().id(1).name("Passport").requiresVerification(true).requiresExpiryDate(false).build();
        lenient().when(docTypeRepo.findById(1)).thenReturn(Optional.of(docType));

        lenient().when(docRepo.save(any(EmployeeDocument.class))).thenAnswer(inv -> {
            EmployeeDocument d = inv.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });
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

    @Test
    void uploadDocument_firstUpload_createsVersion1AsCurrent() throws Exception {
        when(docRepo.findByEmployeeUserIdAndDocumentTypeIdAndSupersededFalse(employeeId, 1)).thenReturn(Optional.empty());

        MockMultipartFile file = new MockMultipartFile("file", "passport.pdf", "application/pdf", "data".getBytes());
        EmployeeDocumentResponse resp = documentService.uploadDocument(EMPLOYEE_EMAIL, 1, file, null, null);

        assertEquals(1, resp.getVersionNumber());
        assertFalse(resp.isSuperseded());
        assertNull(resp.getPreviousVersionId());
        assertEquals("PENDING_VERIFICATION", resp.getStatus());
        verify(docRepo, never()).saveAndFlush(any());
    }

    @Test
    void uploadDocument_reupload_createsNewVersionWithoutMutatingPrevious() throws Exception {
        UUID currentId = UUID.randomUUID();
        EmployeeDocument current = EmployeeDocument.builder()
                .id(currentId)
                .employeeUserId(employeeId)
                .documentType(docType)
                .fileName("old.pdf")
                .fileUrl("/api/documents/" + currentId + "/file")
                .fileData("old-bytes".getBytes())
                .status("REJECTED")
                .rejectionReason("Document image is unclear")
                .versionNumber(1)
                .superseded(false)
                .build();

        when(docRepo.findByEmployeeUserIdAndDocumentTypeIdAndSupersededFalse(employeeId, 1)).thenReturn(Optional.of(current));
        when(docRepo.saveAndFlush(current)).thenReturn(current);

        MockMultipartFile file = new MockMultipartFile("file", "new.pdf", "application/pdf", "new-bytes".getBytes());
        EmployeeDocumentResponse resp = documentService.uploadDocument(EMPLOYEE_EMAIL, 1, file, null, null);

        // AC1/AC3 — previous row is marked superseded but its status/reason/file are untouched.
        assertTrue(current.isSuperseded());
        assertEquals("REJECTED", current.getStatus());
        assertEquals("Document image is unclear", current.getRejectionReason());
        assertArrayEquals("old-bytes".getBytes(), current.getFileData());

        // AC1 — new row is a new id, current, version incremented, linked back to the previous one.
        assertNotEquals(currentId, resp.getId());
        assertEquals(2, resp.getVersionNumber());
        assertFalse(resp.isSuperseded());
        assertEquals(currentId, resp.getPreviousVersionId());
        assertEquals("PENDING_VERIFICATION", resp.getStatus());
        assertNull(resp.getRejectionReason());

        // The supersede update must be flushed before the new version is inserted, or both rows
        // would briefly be non-superseded at once (see uploadDocument's comment / V190's partial
        // unique index).
        InOrder inOrder = inOrder(docRepo);
        inOrder.verify(docRepo).saveAndFlush(current);
        inOrder.verify(docRepo, atLeastOnce()).save(any(EmployeeDocument.class));
    }

    @Test
    void uploadDocument_thirdUpload_incrementsVersionAndChainsToSecond() throws Exception {
        UUID v1Id = UUID.randomUUID();
        UUID v2Id = UUID.randomUUID();
        EmployeeDocument v2 = EmployeeDocument.builder()
                .id(v2Id).employeeUserId(employeeId).documentType(docType)
                .fileName("v2.pdf").fileUrl("/api/documents/" + v2Id + "/file").fileData("v2".getBytes())
                .status("REJECTED").rejectionReason("Expiry date not visible")
                .versionNumber(2).previousVersionId(v1Id).superseded(false)
                .build();

        when(docRepo.findByEmployeeUserIdAndDocumentTypeIdAndSupersededFalse(employeeId, 1)).thenReturn(Optional.of(v2));
        when(docRepo.saveAndFlush(v2)).thenReturn(v2);

        MockMultipartFile file = new MockMultipartFile("file", "v3.pdf", "application/pdf", "v3".getBytes());
        EmployeeDocumentResponse resp = documentService.uploadDocument(EMPLOYEE_EMAIL, 1, file, null, null);

        assertEquals(3, resp.getVersionNumber());
        assertEquals(v2Id, resp.getPreviousVersionId());
        assertTrue(v2.isSuperseded());
    }

    @Test
    void uploadDocument_currentIsPendingReview_isRejected() {
        UUID currentId = UUID.randomUUID();
        EmployeeDocument current = EmployeeDocument.builder()
                .id(currentId).employeeUserId(employeeId).documentType(docType)
                .fileName("pending.pdf").fileUrl("/api/documents/" + currentId + "/file").fileData("data".getBytes())
                .status("PENDING_VERIFICATION").versionNumber(1).superseded(false)
                .build();
        when(docRepo.findByEmployeeUserIdAndDocumentTypeIdAndSupersededFalse(employeeId, 1)).thenReturn(Optional.of(current));

        MockMultipartFile file = new MockMultipartFile("file", "new.pdf", "application/pdf", "new-bytes".getBytes());
        assertThrows(IllegalStateException.class,
                () -> documentService.uploadDocument(EMPLOYEE_EMAIL, 1, file, null, null));

        // Must not touch the pending row at all — withdraw() is the only way to move it aside.
        verify(docRepo, never()).saveAndFlush(any());
        verify(docRepo, never()).save(any(EmployeeDocument.class));
    }

    // ── withdrawDocument (Pending Review only) ───────────────────────────────

    @Test
    void withdrawDocument_pendingDocument_marksWithdrawnAndSuperseded() {
        UUID docId = UUID.randomUUID();
        EmployeeDocument doc = EmployeeDocument.builder()
                .id(docId).employeeUserId(employeeId).documentType(docType)
                .fileName("v1.pdf").fileUrl("x").fileData(new byte[0])
                .status("PENDING_VERIFICATION").versionNumber(1).superseded(false).build();
        when(docRepo.findById(docId)).thenReturn(Optional.of(doc));

        EmployeeDocumentResponse resp = documentService.withdrawDocument(EMPLOYEE_EMAIL, docId);

        assertEquals("WITHDRAWN", resp.getStatus());
        assertTrue(resp.isSuperseded());
        // No new row is inserted — unlike a re-upload, withdrawing doesn't create a replacement.
        verify(docRepo, never()).saveAndFlush(any());
    }

    @Test
    void withdrawDocument_verifiedDocument_isRejected() {
        UUID docId = UUID.randomUUID();
        EmployeeDocument doc = EmployeeDocument.builder()
                .id(docId).employeeUserId(employeeId).documentType(docType)
                .fileName("v1.pdf").fileUrl("x").fileData(new byte[0])
                .status("VERIFIED").versionNumber(1).superseded(false).build();
        when(docRepo.findById(docId)).thenReturn(Optional.of(doc));

        assertThrows(IllegalStateException.class, () -> documentService.withdrawDocument(EMPLOYEE_EMAIL, docId));
    }

    @Test
    void withdrawDocument_alreadySupersededDocument_isRejected() {
        UUID docId = UUID.randomUUID();
        EmployeeDocument doc = EmployeeDocument.builder()
                .id(docId).employeeUserId(employeeId).documentType(docType)
                .fileName("v1.pdf").fileUrl("x").fileData(new byte[0])
                .status("PENDING_VERIFICATION").versionNumber(1).superseded(true).build();
        when(docRepo.findById(docId)).thenReturn(Optional.of(doc));

        assertThrows(IllegalStateException.class, () -> documentService.withdrawDocument(EMPLOYEE_EMAIL, docId));
    }

    @Test
    void withdrawDocument_nonOwner_isDenied() {
        String otherEmail = "other@test.com";
        User other = User.builder().id(UUID.randomUUID()).email(otherEmail)
                .roles(new HashSet<>(Set.of(role("EMPLOYEE")))).build();
        when(userRepo.findByEmail(otherEmail)).thenReturn(Optional.of(other));

        UUID docId = UUID.randomUUID();
        EmployeeDocument doc = EmployeeDocument.builder()
                .id(docId).employeeUserId(employeeId).documentType(docType)
                .fileName("v1.pdf").fileUrl("x").fileData(new byte[0])
                .status("PENDING_VERIFICATION").versionNumber(1).superseded(false).build();
        when(docRepo.findById(docId)).thenReturn(Optional.of(doc));

        assertThrows(AccessDeniedException.class, () -> documentService.withdrawDocument(otherEmail, docId));
    }

    @Test
    void uploadDocument_afterWithdraw_startsFreshVersionChain() throws Exception {
        // Simulates the state left behind by withdrawDocument: current=false, so the next upload
        // is treated exactly like a first-ever submission, not a re-upload of the withdrawn row.
        when(docRepo.findByEmployeeUserIdAndDocumentTypeIdAndSupersededFalse(employeeId, 1)).thenReturn(Optional.empty());

        MockMultipartFile file = new MockMultipartFile("file", "fresh.pdf", "application/pdf", "data".getBytes());
        EmployeeDocumentResponse resp = documentService.uploadDocument(EMPLOYEE_EMAIL, 1, file, null, null);

        assertEquals(1, resp.getVersionNumber());
        assertNull(resp.getPreviousVersionId());
        assertEquals("PENDING_VERIFICATION", resp.getStatus());
    }

    @Test
    void getDocumentHistory_returnsAllVersionsNewestFirst() {
        UUID v1Id = UUID.randomUUID();
        UUID v2Id = UUID.randomUUID();
        EmployeeDocument v1 = EmployeeDocument.builder()
                .id(v1Id).employeeUserId(employeeId).documentType(docType)
                .fileName("v1.pdf").fileUrl("x").fileData(new byte[0])
                .status("REJECTED").rejectionReason("unclear").versionNumber(1).superseded(true).build();
        EmployeeDocument v2 = EmployeeDocument.builder()
                .id(v2Id).employeeUserId(employeeId).documentType(docType)
                .fileName("v2.pdf").fileUrl("x").fileData(new byte[0])
                .status("PENDING_VERIFICATION").versionNumber(2).previousVersionId(v1Id).superseded(false).build();

        when(docRepo.findById(v2Id)).thenReturn(Optional.of(v2));
        when(docRepo.findByEmployeeUserIdAndDocumentTypeIdOrderByVersionNumberDesc(employeeId, 1))
                .thenReturn(List.of(v2, v1));

        List<EmployeeDocumentResponse> history = documentService.getDocumentHistory(EMPLOYEE_EMAIL, v2Id);

        assertEquals(2, history.size());
        assertEquals(2, history.get(0).getVersionNumber());
        assertFalse(history.get(0).isSuperseded());
        assertEquals(1, history.get(1).getVersionNumber());
        assertTrue(history.get(1).isSuperseded());
        assertEquals("unclear", history.get(1).getRejectionReason());
    }

    @Test
    void getDocumentHistory_deniesNonOwnerNonAdmin() {
        String otherEmail = "other@test.com";
        User other = User.builder().id(UUID.randomUUID()).email(otherEmail)
                .roles(new HashSet<>(Set.of(role("EMPLOYEE")))).build();
        when(userRepo.findByEmail(otherEmail)).thenReturn(Optional.of(other));

        UUID docId = UUID.randomUUID();
        EmployeeDocument doc = EmployeeDocument.builder()
                .id(docId).employeeUserId(employeeId).documentType(docType)
                .fileName("v1.pdf").fileUrl("x").fileData(new byte[0])
                .status("PENDING_VERIFICATION").versionNumber(1).superseded(false).build();
        when(docRepo.findById(docId)).thenReturn(Optional.of(doc));

        assertThrows(AccessDeniedException.class, () -> documentService.getDocumentHistory(otherEmail, docId));
    }

    @Test
    void myDocuments_usesCurrentVersionOnlyQuery() {
        when(docRepo.findByEmployeeUserIdAndSupersededFalseOrderByUploadedAtDesc(employeeId)).thenReturn(List.of());

        documentService.myDocuments(EMPLOYEE_EMAIL);

        verify(docRepo, atLeastOnce()).findByEmployeeUserIdAndSupersededFalseOrderByUploadedAtDesc(employeeId);
        verify(docRepo, never()).findByEmployeeUserIdOrderByUploadedAtDesc(any());
    }

    // ── documentsForEmployee (Onboarding "View Documents") ──────────────────

    @Test
    void documentsForEmployee_adminCaller_returnsOnlyThatEmployeesCurrentDocuments() {
        String adminEmail = "admin@test.com";
        User admin = User.builder().id(UUID.randomUUID()).email(adminEmail)
                .roles(new HashSet<>(Set.of(role("HR_ADMIN")))).build();
        when(userRepo.findByEmail(adminEmail)).thenReturn(Optional.of(admin));
        when(employeeRepo.findById(employeeId)).thenReturn(Optional.of(
                Employee.builder().userId(employeeId).fullName("Jane Doe").employeeCode("E1").build()));

        EmployeeDocument doc = EmployeeDocument.builder()
                .id(UUID.randomUUID()).employeeUserId(employeeId).documentType(docType)
                .fileName("passport.pdf").fileUrl("x").fileData(new byte[0])
                .status("VERIFIED").versionNumber(1).superseded(false).build();
        when(docRepo.findByEmployeeUserIdAndSupersededFalseOrderByUploadedAtDesc(employeeId)).thenReturn(List.of(doc));

        List<EmployeeDocumentResponse> docs = documentService.documentsForEmployee(adminEmail, employeeId);

        assertEquals(1, docs.size());
        assertEquals(employeeId, docs.get(0).getEmployeeUserId());
        assertEquals("Jane Doe", docs.get(0).getEmployeeName());
        verify(docRepo).findByEmployeeUserIdAndSupersededFalseOrderByUploadedAtDesc(employeeId);
        // Scoped strictly to this employeeId — never a global/other-employee query.
        verify(docRepo, never()).findAllWithActiveEmployee();
    }

    @Test
    void documentsForEmployee_nonAdminCaller_isDenied() {
        assertThrows(AccessDeniedException.class, () -> documentService.documentsForEmployee(EMPLOYEE_EMAIL, employeeId));
    }

    // ── Bug: "HR Admin: unable to verify or reject uploaded employee documents" ─────────────────
    //
    // Root cause: listPending/listAll/getAdminKpis used to exclude every document belonging to
    // ANY HR_ADMIN/SUPER_ADMIN-role employee from EVERY admin's view (via userRepo.findAdminUserIds()),
    // not just from that document owner's OWN view. A document uploaded by one HR Admin (e.g.
    // before they were promoted from Employee) was therefore invisible in every admin list and
    // permanently stuck PENDING_VERIFICATION — nobody could ever discover its id to verify/reject
    // it, even though verifyDocument() itself only ever blocked the OWNER from self-reviewing.
    // Fix: scope the exclusion to the CALLER's own id instead of every admin-role employee.

    @Test
    void listPending_anotherAdminsDocument_isStillVisibleToTheCaller() {
        String hrAdminEmail = "hr.admin@test.com";
        User hrAdmin = User.builder().id(UUID.randomUUID()).email(hrAdminEmail)
                .roles(new HashSet<>(Set.of(role("HR_ADMIN")))).build();
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdmin));

        // The document owner (employeeId) is itself an HR Admin — a different admin from the
        // caller — and must still show up for the caller to act on.
        EmployeeDocument doc = EmployeeDocument.builder()
                .id(UUID.randomUUID()).employeeUserId(employeeId).documentType(docType)
                .fileName("passport.pdf").fileUrl("x").fileData(new byte[0])
                .status("PENDING_VERIFICATION").versionNumber(1).superseded(false).build();
        when(docRepo.findByStatusOrderByUploadedAtDesc("PENDING_VERIFICATION")).thenReturn(List.of(doc));

        List<EmployeeDocumentResponse> pending = documentService.listPending(hrAdminEmail);

        assertEquals(1, pending.size());
        assertEquals(employeeId, pending.get(0).getEmployeeUserId());
        verify(userRepo, never()).findAdminUserIds();
    }

    @Test
    void listPending_excludesTheCallersOwnDocument() {
        String hrAdminEmail = "hr.admin@test.com";
        UUID hrAdminId = UUID.randomUUID();
        User hrAdmin = User.builder().id(hrAdminId).email(hrAdminEmail)
                .roles(new HashSet<>(Set.of(role("HR_ADMIN")))).build();
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdmin));

        EmployeeDocument ownDoc = EmployeeDocument.builder()
                .id(UUID.randomUUID()).employeeUserId(hrAdminId).documentType(docType)
                .fileName("own.pdf").fileUrl("x").fileData(new byte[0])
                .status("PENDING_VERIFICATION").versionNumber(1).superseded(false).build();
        when(docRepo.findByStatusOrderByUploadedAtDesc("PENDING_VERIFICATION")).thenReturn(List.of(ownDoc));

        List<EmployeeDocumentResponse> pending = documentService.listPending(hrAdminEmail);

        assertTrue(pending.isEmpty(), "an admin must never see their own document in the review queue");
    }

    @Test
    void listAll_anotherAdminsDocument_isStillVisibleToTheCaller() {
        String superAdminEmail = "super.admin@test.com";
        User superAdmin = User.builder().id(UUID.randomUUID()).email(superAdminEmail)
                .roles(new HashSet<>(Set.of(role("SUPER_ADMIN")))).build();
        when(userRepo.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdmin));

        EmployeeDocument doc = EmployeeDocument.builder()
                .id(UUID.randomUUID()).employeeUserId(employeeId).documentType(docType)
                .fileName("passport.pdf").fileUrl("x").fileData(new byte[0])
                .status("VERIFIED").versionNumber(1).superseded(false).build();
        when(docRepo.findAllWithActiveEmployee()).thenReturn(List.of(doc));

        List<EmployeeDocumentResponse> all = documentService.listAll(superAdminEmail);

        assertEquals(1, all.size());
        verify(userRepo, never()).findAdminUserIds();
    }
}
