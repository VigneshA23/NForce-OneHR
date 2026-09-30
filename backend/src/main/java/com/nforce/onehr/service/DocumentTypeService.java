package com.nforce.onehr.service;

import com.nforce.onehr.dto.doc.*;
import com.nforce.onehr.entity.DocumentType;
import com.nforce.onehr.repository.DocumentTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DocumentTypeService {

    private final DocumentTypeRepository docTypeRepo;

    @Transactional(readOnly = true)
    public List<DocumentTypeResponse> listAll() {
        return docTypeRepo.findAll().stream()
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
        if (docTypeRepo.existsByNameIgnoreCase(req.getName())) {
            throw new IllegalStateException("Document type '" + req.getName() + "' already exists");
        }
        DocumentType dt = DocumentType.builder()
                .name(req.getName())
                .requiresVerification(req.isRequiresVerification())
                .requiresExpiryDate(req.isRequiresExpiryDate())
                .applicableEmploymentTypes(req.getApplicableEmploymentTypes())
                .applicableLocations(req.getApplicableLocations())
                .build();
        docTypeRepo.save(dt);
        return DocumentTypeResponse.from(dt, 0L);
    }

    @Transactional
    public DocumentTypeResponse update(Integer id, UpdateDocumentTypeRequest req) {
        DocumentType dt = docTypeRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("Document type not found: " + id));
        if (req.getName() != null && !req.getName().equalsIgnoreCase(dt.getName())) {
            if (docTypeRepo.existsByNameIgnoreCase(req.getName())) {
                throw new IllegalStateException("Document type '" + req.getName() + "' already exists");
            }
            dt.setName(req.getName());
        }
        if (req.getRequiresVerification() != null) dt.setRequiresVerification(req.getRequiresVerification());
        if (req.getRequiresExpiryDate() != null) dt.setRequiresExpiryDate(req.getRequiresExpiryDate());
        if (req.getApplicableEmploymentTypes() != null) dt.setApplicableEmploymentTypes(req.getApplicableEmploymentTypes());
        if (req.getApplicableLocations() != null) dt.setApplicableLocations(req.getApplicableLocations());
        docTypeRepo.save(dt);
        return DocumentTypeResponse.from(dt, docTypeRepo.countUsageByTypeId(dt.getId()));
    }

    @Transactional
    public void toggleActive(Integer id) {
        DocumentType dt = docTypeRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("Document type not found: " + id));
        dt.setActive(!dt.isActive());
        docTypeRepo.save(dt);
    }

    @Transactional
    public void delete(Integer id) {
        DocumentType dt = docTypeRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("Document type not found: " + id));
        long usage = docTypeRepo.countUsageByTypeId(id);
        if (usage > 0) {
            throw new IllegalStateException(usage + " employee document(s) use this type. Deactivate instead.");
        }
        docTypeRepo.delete(dt);
    }
}
