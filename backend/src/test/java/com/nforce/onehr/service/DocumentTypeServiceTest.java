package com.nforce.onehr.service;

import com.nforce.onehr.controller.DocumentTypeController;
import com.nforce.onehr.dto.doc.CreateDocumentTypeRequest;
import com.nforce.onehr.dto.doc.DocumentTypeResponse;
import com.nforce.onehr.dto.doc.UpdateDocumentTypeRequest;
import com.nforce.onehr.entity.DocumentType;
import com.nforce.onehr.repository.DocumentTypeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentTypeServiceTest {

    @Mock private DocumentTypeRepository docTypeRepo;
    @InjectMocks private DocumentTypeService service;

    private static CreateDocumentTypeRequest createReq(String name) {
        CreateDocumentTypeRequest r = new CreateDocumentTypeRequest();
        r.setName(name);
        return r;
    }

    private static DocumentType existing() {
        return DocumentType.builder().id(7).name("Visa").applicableEmploymentTypes("CONTRACT")
                .applicableLocations("Chennai HQ").build();
    }

    @Test
    void create_trimsNameAndNormalizesApplicability() {
        CreateDocumentTypeRequest r = createReq("  Visa  ");
        r.setApplicableEmploymentTypes(" FULL_TIME , contract,, full_time ");
        r.setApplicableLocations("   ");
        when(docTypeRepo.existsByNameIgnoreCase("Visa")).thenReturn(false);
        when(docTypeRepo.saveAndFlush(any())).thenAnswer(inv -> {
            DocumentType dt = inv.getArgument(0);
            dt.setId(11);
            return dt;
        });

        DocumentTypeResponse res = service.create(r);

        assertEquals("Visa", res.getName());
        assertEquals("FULL_TIME,contract", res.getApplicableEmploymentTypes(), "trimmed, blanks dropped, case-insensitive de-dupe");
        assertNull(res.getApplicableLocations(), "blank applicability is stored as null = applies to everyone");
        assertTrue(res.isActive());
    }

    @Test
    void create_duplicateNameCaseInsensitive_rejected() {
        when(docTypeRepo.existsByNameIgnoreCase("identity document")).thenReturn(true);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.create(createReq("identity document")));
        assertTrue(e.getMessage().contains("already exists"));
        verify(docTypeRepo, never()).saveAndFlush(any());
    }

    @Test
    void create_dbUniqueConstraintRace_mapsToDuplicateMessage() {
        when(docTypeRepo.existsByNameIgnoreCase("Visa")).thenReturn(false);
        when(docTypeRepo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.create(createReq("Visa")));
        assertTrue(e.getMessage().contains("already exists"));
    }

    @Test
    void create_blankName_rejected() {
        assertThrows(IllegalArgumentException.class, () -> service.create(createReq("   ")));
    }

    @Test
    void update_renameToExistingName_rejected() {
        when(docTypeRepo.findById(7)).thenReturn(Optional.of(existing()));
        when(docTypeRepo.existsByNameIgnoreCase("Passport")).thenReturn(true);
        UpdateDocumentTypeRequest r = new UpdateDocumentTypeRequest();
        r.setName("Passport");

        assertThrows(IllegalStateException.class, () -> service.update(7, r));
    }

    @Test
    void update_emptyStringClearsApplicability_nullLeavesUnchanged() {
        DocumentType dt = existing();
        when(docTypeRepo.findById(7)).thenReturn(Optional.of(dt));
        UpdateDocumentTypeRequest r = new UpdateDocumentTypeRequest();
        r.setApplicableEmploymentTypes("");
        r.setRequiresExpiryDate(true);

        service.update(7, r);

        assertNull(dt.getApplicableEmploymentTypes(), "empty string clears back to 'everyone'");
        assertEquals("Chennai HQ", dt.getApplicableLocations(), "null field is left unchanged");
        assertTrue(dt.isRequiresExpiryDate());
    }

    @Test
    void toggleActive_flipsFlagOnlyOnTheType() {
        DocumentType dt = existing();
        when(docTypeRepo.findById(7)).thenReturn(Optional.of(dt));

        service.toggleActive(7);
        assertFalse(dt.isActive());
        service.toggleActive(7);
        assertTrue(dt.isActive());
        // Nothing but the type itself is written — uploaded EmployeeDocuments are untouched.
        ArgumentCaptor<DocumentType> saved = ArgumentCaptor.forClass(DocumentType.class);
        verify(docTypeRepo, times(2)).save(saved.capture());
        verifyNoMoreInteractions(ignoreStubs(docTypeRepo));
    }

    @Test
    void delete_inUse_blockedWithUsageCountAndDeactivateAdvice() {
        when(docTypeRepo.findById(7)).thenReturn(Optional.of(existing()));
        when(docTypeRepo.countUsageByTypeId(7)).thenReturn(3L);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.delete(7));
        assertTrue(e.getMessage().contains("3 employee document(s)"), e.getMessage());
        assertTrue(e.getMessage().contains("Deactivate"), e.getMessage());
        verify(docTypeRepo, never()).delete(any());
    }

    @Test
    void delete_unused_deletes() {
        DocumentType dt = existing();
        when(docTypeRepo.findById(7)).thenReturn(Optional.of(dt));
        when(docTypeRepo.countUsageByTypeId(7)).thenReturn(0L);

        service.delete(7);

        verify(docTypeRepo).delete(dt);
    }

    @Test
    void controller_mutationsRestrictedToHrAndSuperAdmin() {
        for (String m : new String[]{"listAll", "create", "update", "toggleActive", "delete"}) {
            Method method = Arrays.stream(DocumentTypeController.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(m)).findFirst().orElseThrow();
            PreAuthorize pa = method.getAnnotation(PreAuthorize.class);
            assertNotNull(pa, m + " must be @PreAuthorize-protected");
            assertEquals("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')", pa.value(), m);
        }
    }
}
