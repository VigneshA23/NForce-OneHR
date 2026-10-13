package com.nforce.onehr.service;

import com.nforce.onehr.controller.DocumentController;
import com.nforce.onehr.dto.doc.*;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Admin: Manage Document Types (applicability / verification / deactivation effects) and
 * HR Admin: Verify or Reject Uploaded Employee Documents.
 */
@ExtendWith(MockitoExtension.class)
class DocumentComplianceServiceTest {

    @Mock private EmployeeDocumentRepository docRepo;
    @Mock private DocumentTypeRepository docTypeRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private UserRepository userRepo;
    @Mock private NotificationRepository notificationRepo;
    @Mock private NotificationService notificationService;

    private DocumentService service;

    private static final String EMP_EMAIL = "emp@test.com";
    private static final String OTHER_EMP_EMAIL = "other@test.com";
    private static final String HR_EMAIL = "hr@test.com";
    private static final String SA_EMAIL = "sa@test.com";
    private final UUID empId = UUID.randomUUID();
    private final UUID otherEmpId = UUID.randomUUID();
    private final UUID hrId = UUID.randomUUID();
    private final UUID saId = UUID.randomUUID();
    private User empUser;

    @BeforeEach
    void setUp() {
        service = new DocumentService(docRepo, docTypeRepo, employeeRepo, userRepo, notificationRepo, notificationService);
        empUser = user(empId, EMP_EMAIL, "EMPLOYEE");
        lenient().when(userRepo.findByEmail(EMP_EMAIL)).thenReturn(Optional.of(empUser));
        lenient().when(userRepo.findByEmail(OTHER_EMP_EMAIL)).thenReturn(Optional.of(user(otherEmpId, OTHER_EMP_EMAIL, "EMPLOYEE")));
        lenient().when(userRepo.findByEmail(HR_EMAIL)).thenReturn(Optional.of(user(hrId, HR_EMAIL, "HR_ADMIN")));
        lenient().when(userRepo.findByEmail(SA_EMAIL)).thenReturn(Optional.of(user(saId, SA_EMAIL, "SUPER_ADMIN")));
        lenient().when(userRepo.findAdminUserIds()).thenReturn(Set.of(hrId, saId));
        lenient().when(docRepo.save(any(EmployeeDocument.class))).thenAnswer(inv -> {
            EmployeeDocument d = inv.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });
    }

    private static User user(UUID id, String email, String roleCode) {
        Role r = Role.builder().id(roleCode.hashCode()).code(roleCode).displayName(roleCode).build();
        return User.builder().id(id).email(email).roles(new HashSet<>(Set.of(r))).build();
    }

    private Employee employee(String employmentType, String locationName) {
        return Employee.builder().userId(empId).user(empUser).fullName("Emp One")
                .employmentType(employmentType)
                .location(locationName == null ? null : Location.builder().name(locationName).build())
                .build();
    }

    private static DocumentType type(int id, String name, String empTypes, String locs) {
        return DocumentType.builder().id(id).name(name).applicableEmploymentTypes(empTypes).applicableLocations(locs).build();
    }

    private static EmployeeDocument doc(UUID owner, DocumentType dt, String status) {
        return EmployeeDocument.builder().id(UUID.randomUUID()).employeeUserId(owner).documentType(dt)
                .fileName("f.pdf").fileUrl("x").fileData(new byte[0]).status(status).build();
    }

    private static VerifyDocumentRequest action(String a, String reason) {
        VerifyDocumentRequest r = new VerifyDocumentRequest();
        r.setAction(a);
        r.setRejectionReason(reason);
        return r;
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "a.pdf", "application/pdf", "data".getBytes());
    }

    // ── Upload: verification flag, inactive types, expiry ──

    @Test
    void upload_verificationRequired_startsPendingReview() throws Exception {
        DocumentType dt = type(1, "Identity Document", null, null);
        dt.setRequiresVerification(true);
        when(docTypeRepo.findById(1)).thenReturn(Optional.of(dt));

        assertEquals("PENDING_VERIFICATION", service.uploadDocument(EMP_EMAIL, 1, pdf(), null, null).getStatus());
    }

    @Test
    void upload_verificationNotRequired_isVerifiedImmediately() throws Exception {
        DocumentType dt = type(5, "Personal Document", null, null);
        dt.setRequiresVerification(false);
        when(docTypeRepo.findById(5)).thenReturn(Optional.of(dt));

        assertEquals("VERIFIED", service.uploadDocument(EMP_EMAIL, 5, pdf(), null, null).getStatus());
    }

    @Test
    void upload_inactiveType_rejected() {
        DocumentType dt = type(1, "Old Type", null, null);
        dt.setActive(false);
        when(docTypeRepo.findById(1)).thenReturn(Optional.of(dt));

        assertThrows(IllegalStateException.class, () -> service.uploadDocument(EMP_EMAIL, 1, pdf(), null, null));
        verify(docRepo, never()).save(any());
    }

    @Test
    void upload_expiryRequiredButMissing_rejected() {
        DocumentType dt = type(1, "Work Authorization", null, null);
        dt.setRequiresExpiryDate(true);
        when(docTypeRepo.findById(1)).thenReturn(Optional.of(dt));

        assertThrows(IllegalArgumentException.class, () -> service.uploadDocument(EMP_EMAIL, 1, pdf(), null, null));
    }

    // ── Applicability (required-document list) ──

    private List<String> requiredNamesFor(Employee emp, DocumentType... activeTypes) {
        when(employeeRepo.findById(empId)).thenReturn(Optional.of(emp));
        when(docTypeRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(activeTypes));
        when(docRepo.findByEmployeeUserIdAndSupersededFalseOrderByUploadedAtDesc(empId)).thenReturn(List.of());
        return service.requiredDocumentsFor(empId).stream().map(RequiredDocumentDto::getDocumentTypeName).toList();
    }

    @Test
    void applicability_employmentType_caseInsensitive() {
        List<String> names = requiredNamesFor(employee("CONTRACT", null),
                type(1, "Contractor NDA", " full_time, contract ", null),
                type(2, "Intern Letter", "INTERN", null));
        assertEquals(List.of("Contractor NDA"), names);
    }

    @Test
    void applicability_location_caseInsensitive() {
        List<String> names = requiredNamesFor(employee("FULL_TIME", "Chennai HQ"),
                type(1, "TN Form", null, "chennai hq,Bangalore Office"),
                type(2, "KA Form", null, "Bangalore Office"));
        assertEquals(List.of("TN Form"), names);
    }

    @Test
    void applicability_blankAppliesToEveryone() {
        List<String> names = requiredNamesFor(employee("PART_TIME", null),
                type(1, "Identity Document", null, null),
                type(2, "Employment Contract", "", "  "));
        assertEquals(List.of("Identity Document", "Employment Contract"), names);
    }

    @Test
    void deactivatedTypes_excludedFromRequiredChecks() {
        // requiredDocumentsFor/compliance/missing all source types from the active-only query.
        requiredNamesFor(employee("FULL_TIME", null), type(1, "Identity Document", null, null));
        verify(docTypeRepo).findByActiveTrueOrderByNameAsc();
        verify(docTypeRepo, never()).findAll();
    }

    @Test
    void myDocuments_stillReturnsDocsOfDeactivatedType() {
        DocumentType inactive = type(9, "Retired Type", null, null);
        inactive.setActive(false);
        EmployeeDocument existingDoc = doc(empId, inactive, "VERIFIED");
        when(docRepo.findByEmployeeUserIdAndSupersededFalseOrderByUploadedAtDesc(empId)).thenReturn(List.of(existingDoc));

        List<EmployeeDocumentResponse> mine = service.myDocuments(EMP_EMAIL);

        assertEquals(1, mine.size());
        assertEquals("VERIFIED", mine.get(0).getStatus());
    }

    // ── Verify / reject ──

    @Test
    void verify_approve_setsVerifiedAndNotifiesEmployee() {
        EmployeeDocument d = doc(empId, type(1, "Identity Document", null, null), "PENDING_VERIFICATION");
        when(docRepo.findById(d.getId())).thenReturn(Optional.of(d));

        EmployeeDocumentResponse res = service.verifyDocument(HR_EMAIL, d.getId(), action("VERIFY", null));

        assertEquals("VERIFIED", res.getStatus());
        assertEquals(hrId, res.getVerifiedBy());
        verify(notificationService).send(eq(empId), eq("DOCUMENT_VERIFIED"), anyString(), anyString(), eq("/documents"));
    }

    @Test
    void verify_superAdminCanApprove() {
        EmployeeDocument d = doc(empId, type(1, "Identity Document", null, null), "PENDING_VERIFICATION");
        when(docRepo.findById(d.getId())).thenReturn(Optional.of(d));

        assertEquals("VERIFIED", service.verifyDocument(SA_EMAIL, d.getId(), action("VERIFY", null)).getStatus());
    }

    @Test
    void verify_rejectWithReason_storesTrimmedReasonAndNotifies() {
        EmployeeDocument d = doc(empId, type(1, "Identity Document", null, null), "PENDING_VERIFICATION");
        when(docRepo.findById(d.getId())).thenReturn(Optional.of(d));

        EmployeeDocumentResponse res = service.verifyDocument(HR_EMAIL, d.getId(), action("REJECT", "  Blurry scan  "));

        assertEquals("REJECTED", res.getStatus());
        assertEquals("Blurry scan", res.getRejectionReason());
        verify(notificationService).send(eq(empId), eq("DOCUMENT_REJECTED"), anyString(), contains("Blurry scan"), eq("/documents"));
    }

    @Test
    void verify_rejectWithoutReason_blocked() {
        EmployeeDocument d = doc(empId, type(1, "Identity Document", null, null), "PENDING_VERIFICATION");
        when(docRepo.findById(d.getId())).thenReturn(Optional.of(d));

        assertThrows(IllegalArgumentException.class, () -> service.verifyDocument(HR_EMAIL, d.getId(), action("REJECT", "   ")));
        assertEquals("PENDING_VERIFICATION", d.getStatus());
        verifyNoInteractions(notificationService);
    }

    @Test
    void verify_ownDocument_blocked() {
        EmployeeDocument d = doc(hrId, type(1, "Identity Document", null, null), "PENDING_VERIFICATION");
        when(docRepo.findById(d.getId())).thenReturn(Optional.of(d));

        assertThrows(AccessDeniedException.class, () -> service.verifyDocument(HR_EMAIL, d.getId(), action("VERIFY", null)));
    }

    @Test
    void verify_byRegularEmployee_blocked() {
        assertThrows(AccessDeniedException.class,
                () -> service.verifyDocument(EMP_EMAIL, UUID.randomUUID(), action("VERIFY", null)));
    }

    @Test
    void verify_alreadyReviewed_blocked() {
        EmployeeDocument d = doc(empId, type(1, "Identity Document", null, null), "VERIFIED");
        when(docRepo.findById(d.getId())).thenReturn(Optional.of(d));

        assertThrows(IllegalStateException.class, () -> service.verifyDocument(HR_EMAIL, d.getId(), action("REJECT", "late")));
    }

    // ── Admin lists / KPIs exclude admins' own documents ──

    @Test
    void listsAndKpis_excludeAdminDocuments_andAgree() {
        DocumentType dt = type(1, "Identity Document", null, null);
        EmployeeDocument empPending = doc(empId, dt, "PENDING_VERIFICATION");
        EmployeeDocument empVerifiedExpiring = doc(empId, type(2, "Work Authorization", null, null), "VERIFIED");
        empVerifiedExpiring.setExpiryDate(LocalDate.now().plusDays(10));
        EmployeeDocument empVerifiedAlreadyExpired = doc(otherEmpId, dt, "VERIFIED");
        empVerifiedAlreadyExpired.setExpiryDate(LocalDate.now().minusDays(1));
        EmployeeDocument hrPending = doc(hrId, dt, "PENDING_VERIFICATION");
        when(docRepo.findAllWithActiveEmployee()).thenReturn(List.of(empPending, empVerifiedExpiring, empVerifiedAlreadyExpired, hrPending));
        when(docRepo.findByStatusOrderByUploadedAtDesc("PENDING_VERIFICATION")).thenReturn(List.of(empPending, hrPending));

        List<EmployeeDocumentResponse> all = service.listAll(HR_EMAIL);
        List<EmployeeDocumentResponse> pending = service.listPending(HR_EMAIL);
        DocumentAdminKpiDto kpis = service.getAdminKpis(HR_EMAIL);

        assertEquals(3, all.size());
        assertTrue(all.stream().noneMatch(d -> d.getEmployeeUserId().equals(hrId)));
        assertEquals(1, pending.size());
        assertEquals(pending.size(), kpis.getPendingVerification());
        assertEquals(1, kpis.getEmployeesWithPending());
        assertEquals(1, kpis.getExpiringWithin30Days(), "already-expired documents are not 'expiring within 30 days'");
        assertEquals(all.size(), kpis.getTotalDocuments(), "total must match the All list, not docRepo.count()");
        verify(docRepo, never()).count();
    }

    @Test
    void listMissing_excludesAdminsAndUploadedTypes() {
        DocumentType idDoc = type(1, "Identity Document", null, null);
        DocumentType contract = type(3, "Employment Contract", null, null);
        when(employeeRepo.findAllActiveNonAdminWithDetails()).thenReturn(List.of(employee("FULL_TIME", null)));
        when(docTypeRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(idDoc, contract));
        when(docRepo.findAllEmployeeDocTypePairs()).thenReturn(List.<Object[]>of(new Object[]{empId, 1}));

        List<MissingDocumentDto> missing = service.listMissing(HR_EMAIL);

        assertEquals(1, missing.size());
        assertEquals("Employment Contract", missing.get(0).getDocumentTypeName());
        assertEquals(empId, missing.get(0).getEmployeeUserId());
    }

    // ── Reminders ──

    @Test
    void remind_notifiesThatEmployeeForThatType() {
        DocumentType contract = type(3, "Employment Contract", null, null);
        when(docTypeRepo.findById(3)).thenReturn(Optional.of(contract));
        when(employeeRepo.findById(empId)).thenReturn(Optional.of(employee("FULL_TIME", null)));
        when(docRepo.findByEmployeeUserIdAndDocumentTypeIdAndSupersededFalse(empId, 3)).thenReturn(Optional.empty());

        service.remindMissingDocument(HR_EMAIL, empId, 3);

        verify(notificationService).send(eq(empId), eq("DOCUMENT_REMINDER"), contains("Employment Contract"), anyString(), eq("/documents"));
    }

    @Test
    void remind_alreadySubmitted_rejected() {
        DocumentType contract = type(3, "Employment Contract", null, null);
        when(docTypeRepo.findById(3)).thenReturn(Optional.of(contract));
        when(employeeRepo.findById(empId)).thenReturn(Optional.of(employee("FULL_TIME", null)));
        when(docRepo.findByEmployeeUserIdAndDocumentTypeIdAndSupersededFalse(empId, 3))
                .thenReturn(Optional.of(doc(empId, contract, "PENDING_VERIFICATION")));

        assertThrows(IllegalStateException.class, () -> service.remindMissingDocument(HR_EMAIL, empId, 3));
        verifyNoInteractions(notificationService);
    }

    @Test
    void remind_byRegularEmployee_blocked() {
        assertThrows(AccessDeniedException.class, () -> service.remindMissingDocument(EMP_EMAIL, otherEmpId, 3));
    }

    // ── File access ──

    @Test
    void file_otherEmployee_denied_ownerAndAdmin_allowed() {
        EmployeeDocument d = doc(empId, type(1, "Identity Document", null, null), "PENDING_VERIFICATION");
        when(docRepo.findById(d.getId())).thenReturn(Optional.of(d));

        assertThrows(AccessDeniedException.class, () -> service.getDocumentFile(OTHER_EMP_EMAIL, d.getId()));
        assertSame(d, service.getDocumentFile(EMP_EMAIL, d.getId()));
        assertSame(d, service.getDocumentFile(HR_EMAIL, d.getId()));
    }

    @Test
    void controller_adminEndpointsRestrictedToHrAndSuperAdmin() {
        for (String m : new String[]{"listAll", "listPending", "verify", "kpis", "listMissing", "remindMissingDocument"}) {
            Method method = Arrays.stream(DocumentController.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(m)).findFirst().orElseThrow();
            PreAuthorize pa = method.getAnnotation(PreAuthorize.class);
            assertNotNull(pa, m + " must be @PreAuthorize-protected");
            assertEquals("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')", pa.value(), m);
        }
    }
}
