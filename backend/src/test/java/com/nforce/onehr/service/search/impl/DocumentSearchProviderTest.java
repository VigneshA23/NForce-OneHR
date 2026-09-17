package com.nforce.onehr.service.search.impl;

import com.nforce.onehr.dto.doc.EmployeeDocumentResponse;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.service.DocumentService;
import com.nforce.onehr.service.search.SearchProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Documents search provider — the permission-critical behavior is which role sees whose
 * documents: Employee/Manager must only ever reach {@link DocumentService#myDocuments} (their own
 * uploads), never {@link DocumentService#listAll} (every employee's), and vice versa for HR
 * Admin/Super Admin. Mockito's strict stubbing (no lenient() here) fails the test outright if the
 * provider ever calls the wrong one.
 */
@ExtendWith(MockitoExtension.class)
class DocumentSearchProviderTest {

    @Mock private DocumentService documentService;
    @InjectMocks private DocumentSearchProvider provider;

    private User userWithRole(String roleCode) {
        return User.builder().id(UUID.randomUUID()).email(roleCode.toLowerCase() + "@test.com")
                .roles(Set.of(Role.builder().code(roleCode).build())).build();
    }

    private EmployeeDocumentResponse doc(UUID id, String typeName, String employeeName, String status) {
        return new EmployeeDocumentResponse(id, UUID.randomUUID(), employeeName, 1, typeName, false, false,
                "file.pdf", "/file.pdf", null, null, status, null, null, null, Instant.now(), Instant.now());
    }

    @Test
    void employee_seesOnlyOwnDocuments_viaMyDocuments() {
        User employee = userWithRole("EMPLOYEE");
        UUID docId = UUID.randomUUID();
        when(documentService.myDocuments(employee.getEmail()))
                .thenReturn(List.of(doc(docId, "PAN Card", "Self", "VERIFIED")));

        SearchProvider.SearchProviderResult result = provider.preview(employee, "pan", 10);

        assertEquals(1, result.items().size());
        assertEquals("/my-documents?tab=docs&search=PAN+Card", result.items().get(0).getDetailUrl());
        verify(documentService, never()).listAll(any());
    }

    @Test
    void manager_seesOnlyOwnDocuments_sameAsEmployee() {
        User manager = userWithRole("MANAGER");
        when(documentService.myDocuments(manager.getEmail()))
                .thenReturn(List.of(doc(UUID.randomUUID(), "Offer Letter", "Self", "VERIFIED")));

        provider.preview(manager, "offer", 10);

        verify(documentService).myDocuments(manager.getEmail());
        verify(documentService, never()).listAll(any());
    }

    @Test
    void hrAdmin_seesOrgWideDocuments_viaListAll() {
        User hrAdmin = userWithRole("HR_ADMIN");
        when(documentService.listAll(hrAdmin.getEmail()))
                .thenReturn(List.of(doc(UUID.randomUUID(), "PAN Card", "John Smith", "PENDING_VERIFICATION")));

        SearchProvider.SearchProviderResult result = provider.preview(hrAdmin, "pan", 10);

        assertEquals(1, result.items().size());
        assertTrue(result.items().get(0).getSubtitle().contains("John Smith"));
        assertEquals("/documents?search=PAN+Card", result.items().get(0).getDetailUrl());
        verify(documentService, never()).myDocuments(any());
    }

    @Test
    void superAdmin_seesOrgWideDocuments_sameAsHrAdmin() {
        User superAdmin = userWithRole("SUPER_ADMIN");
        when(documentService.listAll(superAdmin.getEmail()))
                .thenReturn(List.of(doc(UUID.randomUUID(), "Aadhaar", "Jane Roe", "VERIFIED")));

        provider.preview(superAdmin, "aadhaar", 10);

        verify(documentService).listAll(superAdmin.getEmail());
        verify(documentService, never()).myDocuments(any());
    }

    @Test
    void refineUrl_admin_pointsAtComplianceAdminPage() {
        assertEquals("/documents?search=pan", provider.refineUrl(userWithRole("HR_ADMIN"), "pan"));
    }

    @Test
    void refineUrl_nonAdmin_pointsAtMyDocumentsPage() {
        assertEquals("/my-documents?tab=docs&search=pan", provider.refineUrl(userWithRole("EMPLOYEE"), "pan"));
    }

    @Test
    void noMatches_returnsEmptyResult() {
        User employee = userWithRole("EMPLOYEE");
        when(documentService.myDocuments(employee.getEmail()))
                .thenReturn(List.of(doc(UUID.randomUUID(), "PAN Card", "Self", "VERIFIED")));

        SearchProvider.SearchProviderResult result = provider.preview(employee, "zzz-nomatch", 10);

        assertTrue(result.items().isEmpty());
        assertEquals(0, result.totalCount());
    }
}
