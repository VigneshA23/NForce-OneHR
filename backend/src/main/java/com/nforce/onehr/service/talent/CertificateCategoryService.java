package com.nforce.onehr.service.talent;

import com.nforce.onehr.dto.talent.CertificateCategoryResponse;
import com.nforce.onehr.dto.talent.UpsertCertificateCategoryRequest;
import com.nforce.onehr.entity.CertificateCategory;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.CertificateCategoryRepository;
import com.nforce.onehr.repository.EmployeeCertificateRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.service.AuditService;
import com.nforce.onehr.service.AuditSnapshotSerializer;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/** Settings > Certificate Categories (org.talent.configure) — same CRUD/deactivate-not-delete
 * shape as SkillMasterService/ProficiencyLevelService and DocumentTypeService before them. */
@Service
@RequiredArgsConstructor
public class CertificateCategoryService {

    private final CertificateCategoryRepository categoryRepo;
    private final EmployeeCertificateRepository employeeCertificateRepo;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional(readOnly = true)
    public List<CertificateCategoryResponse> listAll() {
        return categoryRepo.findAllByOrderByNameAsc().stream()
                .map(c -> CertificateCategoryResponse.from(c, employeeCertificateRepo.countByCategoryId(c.getId())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<CertificateCategoryResponse> listActive() {
        return categoryRepo.findByActiveTrueOrderByNameAsc().stream()
                .map(c -> CertificateCategoryResponse.from(c, employeeCertificateRepo.countByCategoryId(c.getId())))
                .collect(Collectors.toList());
    }

    @Transactional
    public CertificateCategoryResponse create(UpsertCertificateCategoryRequest req, String actorEmail) {
        CertificateCategory category = CertificateCategory.builder()
                .name(requireName(req.getName())).hasExpiry(req.isHasExpiry()).build();
        saveOrRejectDuplicate(category);
        auditService.log(actorId(actorEmail), "CERTIFICATE_CATEGORY_CREATED", category.getId(),
                null, auditSnapshot.toJson(Map.of("name", category.getName())));
        return CertificateCategoryResponse.from(category, 0);
    }

    @Transactional
    public CertificateCategoryResponse update(UUID id, UpsertCertificateCategoryRequest req, String actorEmail) {
        CertificateCategory category = requireCategory(id);
        String before = category.getName();
        category.setName(requireName(req.getName()));
        category.setHasExpiry(req.isHasExpiry());
        saveOrRejectDuplicate(category);
        auditService.log(actorId(actorEmail), "CERTIFICATE_CATEGORY_UPDATED", category.getId(),
                auditSnapshot.toJson(Map.of("name", before)), auditSnapshot.toJson(Map.of("name", category.getName())));
        return CertificateCategoryResponse.from(category, employeeCertificateRepo.countByCategoryId(id));
    }

    @Transactional
    public void setActive(UUID id, boolean active, String actorEmail) {
        CertificateCategory category = requireCategory(id);
        category.setActive(active);
        categoryRepo.save(category);
        auditService.log(actorId(actorEmail),
                active ? "CERTIFICATE_CATEGORY_REACTIVATED" : "CERTIFICATE_CATEGORY_DEACTIVATED",
                category.getId(), null, auditSnapshot.toJson(Map.of("name", category.getName())));
    }

    // ── Helpers ──

    private static String requireName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Certificate category name is required");
        if (name.length() > 100) throw new IllegalArgumentException("Certificate category name must be at most 100 characters");
        return name;
    }

    private void saveOrRejectDuplicate(CertificateCategory category) {
        try {
            categoryRepo.saveAndFlush(category);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("Certificate category \"" + category.getName() + "\" already exists");
        }
    }

    private CertificateCategory requireCategory(UUID id) {
        return categoryRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Certificate category not found: " + id));
    }

    private UUID actorId(String actorEmail) {
        return userRepository.findByEmail(actorEmail).map(User::getId).orElse(null);
    }
}
