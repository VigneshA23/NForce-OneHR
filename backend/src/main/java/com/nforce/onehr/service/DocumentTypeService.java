package com.nforce.onehr.service;

import com.nforce.onehr.dto.doc.*;
import com.nforce.onehr.entity.DocumentType;
import com.nforce.onehr.repository.DocumentTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Admin master data for employee document types. Authorization (HR_ADMIN / SUPER_ADMIN) is
 * enforced at DocumentTypeController via @PreAuthorize.
 *
 * <p>Applicability lists are stored as a normalized comma-separated string (trimmed, de-duplicated
 * case-insensitively, blank → null meaning "applies to everyone") and matched case-insensitively
 * against the employee by DocumentService#isApplicable.
 */
@Service
@RequiredArgsConstructor
public class DocumentTypeService {

    private final DocumentTypeRepository docTypeRepo;

    @Transactional(readOnly = true)
    public List<DocumentTypeResponse> listAll() {
        return docTypeRepo.findAll().stream()
                .sorted(Comparator.comparing(DocumentType::getName, String.CASE_INSENSITIVE_ORDER))
                .map(dt -> DocumentTypeResponse.from(dt, docTypeRepo.countUsageByTypeId(dt.getId())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DocumentTypeResponse> listActive() {
        return docTypeRepo.findByActiveTrueOrderByNameAsc().stream()
                .map(dt -> DocumentTypeResponse.from(dt, docTypeRepo.countUsageByTypeId(dt.getId())))
                .collect(Collectors.toList());
    }

    @Transactional
    public DocumentTypeResponse create(CreateDocumentTypeRequest req) {
        String name = requireName(req.getName());
        if (docTypeRepo.existsByNameIgnoreCase(name)) {
            throw duplicate(name);
        }
        DocumentType dt = DocumentType.builder()
                .name(name)
                .requiresVerification(req.isRequiresVerification())
                .requiresExpiryDate(req.isRequiresExpiryDate())
                .applicableEmploymentTypes(normalizeCsv(req.getApplicableEmploymentTypes()))
                .applicableLocations(normalizeCsv(req.getApplicableLocations()))
                .build();
        saveOrRejectDuplicate(dt);
        return DocumentTypeResponse.from(dt, 0L);
    }

    /**
     * PATCH semantics: a null field is left unchanged. For the two applicability lists an empty
     * string is the explicit way to clear them back to "applies to everyone".
     */
    @Transactional
    public DocumentTypeResponse update(Integer id, UpdateDocumentTypeRequest req) {
        DocumentType dt = requireType(id);
        if (req.getName() != null) {
            String name = requireName(req.getName());
            if (!name.equalsIgnoreCase(dt.getName()) && docTypeRepo.existsByNameIgnoreCase(name)) {
                throw duplicate(name);
            }
            dt.setName(name);
        }
        if (req.getRequiresVerification() != null) dt.setRequiresVerification(req.getRequiresVerification());
        if (req.getRequiresExpiryDate() != null) dt.setRequiresExpiryDate(req.getRequiresExpiryDate());
        if (req.getApplicableEmploymentTypes() != null) dt.setApplicableEmploymentTypes(normalizeCsv(req.getApplicableEmploymentTypes()));
        if (req.getApplicableLocations() != null) dt.setApplicableLocations(normalizeCsv(req.getApplicableLocations()));
        saveOrRejectDuplicate(dt);
        return DocumentTypeResponse.from(dt, docTypeRepo.countUsageByTypeId(dt.getId()));
    }

    /**
     * Deactivating only hides the type from new required-document / compliance / missing lists
     * (those read findByActiveTrue...). Already-uploaded employee documents are never touched.
     */
    @Transactional
    public void toggleActive(Integer id) {
        DocumentType dt = requireType(id);
        dt.setActive(!dt.isActive());
        docTypeRepo.save(dt);
    }

    @Transactional
    public void delete(Integer id) {
        DocumentType dt = requireType(id);
        long usage = docTypeRepo.countUsageByTypeId(id);
        if (usage > 0) {
            throw new IllegalStateException("Cannot delete \"" + dt.getName() + "\": it is used by " + usage
                    + " employee document(s). Deactivate it instead to stop new uploads while keeping existing documents.");
        }
        docTypeRepo.delete(dt);
    }

    // ── Helpers ──

    static String normalizeCsv(String raw) {
        if (raw == null) return null;
        Map<String, String> unique = new LinkedHashMap<>();
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(s -> unique.putIfAbsent(s.toLowerCase(), s));
        if (unique.isEmpty()) return null;
        String joined = String.join(",", unique.values());
        if (joined.length() > 200) {
            throw new IllegalArgumentException("Applicability list is too long (max 200 characters)");
        }
        return joined;
    }

    private static String requireName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Document type name is required");
        if (name.length() > 80) throw new IllegalArgumentException("Document type name must be at most 80 characters");
        return name;
    }

    // The DB UNIQUE(name) constraint is the last line of defence (e.g. two admins racing); map it
    // to the same clear message as the application-level check instead of the generic 409.
    private void saveOrRejectDuplicate(DocumentType dt) {
        try {
            docTypeRepo.saveAndFlush(dt);
        } catch (DataIntegrityViolationException e) {
            throw duplicate(dt.getName());
        }
    }

    private static IllegalStateException duplicate(String name) {
        return new IllegalStateException("Document type \"" + name + "\" already exists");
    }

    private DocumentType requireType(Integer id) {
        return docTypeRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Document type not found: " + id));
    }
}
