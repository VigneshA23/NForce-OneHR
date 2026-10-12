package com.nforce.onehr.service;

import com.nforce.onehr.dto.certificates.EmployeeCertificateRequest;
import com.nforce.onehr.dto.certificates.EmployeeCertificateResponse;
import com.nforce.onehr.entity.CertificateCategory;
import com.nforce.onehr.entity.EmployeeCertificate;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.CertificateCategoryRepository;
import com.nforce.onehr.repository.EmployeeCertificateRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeCertificateService {

    private final EmployeeCertificateRepository repository;
    private final UserRepository userRepository;
    private final CertificateCategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<EmployeeCertificateResponse> listMine(String actorEmail) {
        UUID actorId = requireActor(actorEmail).getId();
        return listForEmployee(actorId);
    }

    // Caller must already have verified it's entitled to see this employee's certificates (e.g.
    // via DirectReportScopeService#isDirectReport) — no permission check here, unlike listMine.
    @Transactional(readOnly = true)
    public List<EmployeeCertificateResponse> listForEmployee(UUID employeeUserId) {
        List<EmployeeCertificate> rows = repository.findByEmployeeUserIdOrderByCreatedAtAsc(employeeUserId);
        Map<UUID, String> categoryNameById = categoryNameMap();
        return rows.stream().map(e -> toResponse(e, categoryNameById)).collect(Collectors.toList());
    }

    // Same no-permission-check contract as listForEmployee above, for a whole team at once —
    // backs the Team Performance roster's inline certificate badges without one query per
    // employee. Grouped on the raw entity, not the response: EmployeeCertificateResponse itself
    // has no employeeUserId field.
    @Transactional(readOnly = true)
    public Map<UUID, List<EmployeeCertificateResponse>> listForEmployees(Collection<UUID> employeeUserIds) {
        if (employeeUserIds.isEmpty()) return Map.of();
        List<EmployeeCertificate> rows = repository.findByEmployeeUserIdInOrderByCreatedAtAsc(employeeUserIds);
        Map<UUID, String> categoryNameById = categoryNameMap();
        return rows.stream().collect(Collectors.groupingBy(EmployeeCertificate::getEmployeeUserId, LinkedHashMap::new,
                Collectors.mapping(e -> toResponse(e, categoryNameById), Collectors.toList())));
    }

    @Transactional
    public EmployeeCertificateResponse create(String actorEmail, EmployeeCertificateRequest req) {
        UUID actorId = requireActor(actorEmail).getId();
        CertificateCategory category = resolveOptionalCategory(req.getCategoryId());
        EmployeeCertificate entity = EmployeeCertificate.builder()
                .employeeUserId(actorId)
                .name(req.getName().trim())
                .categoryId(category == null ? null : category.getId())
                .issuingOrganization(blankToNull(req.getIssuingOrganization()))
                .credentialId(blankToNull(req.getCredentialId()))
                .credentialUrl(blankToNull(req.getCredentialUrl()))
                .issueDate(req.getIssueDate())
                .expiryDate(req.getExpiryDate())
                .build();
        repository.save(entity);
        return toResponse(entity, category == null ? Map.of() : Map.of(category.getId(), category.getName()));
    }

    @Transactional
    public EmployeeCertificateResponse update(String actorEmail, UUID id, EmployeeCertificateRequest req) {
        UUID actorId = requireActor(actorEmail).getId();
        EmployeeCertificate entity = requireOwned(id, actorId);
        CertificateCategory category = resolveOptionalCategory(req.getCategoryId());
        entity.setName(req.getName().trim());
        entity.setCategoryId(category == null ? null : category.getId());
        entity.setIssuingOrganization(blankToNull(req.getIssuingOrganization()));
        entity.setCredentialId(blankToNull(req.getCredentialId()));
        entity.setCredentialUrl(blankToNull(req.getCredentialUrl()));
        entity.setIssueDate(req.getIssueDate());
        entity.setExpiryDate(req.getExpiryDate());
        repository.save(entity);
        return toResponse(entity, category == null ? Map.of() : Map.of(category.getId(), category.getName()));
    }

    @Transactional
    public void delete(String actorEmail, UUID id) {
        UUID actorId = requireActor(actorEmail).getId();
        EmployeeCertificate entity = requireOwned(id, actorId);
        repository.delete(entity);
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    // Never trusts a client-supplied employeeId — the acting employee is always resolved from the
    // authenticated principal's email, matching EmployeeSkillService's own requireActor pattern.
    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    // Ownership check: {id} in the URL is attacker-controlled — loading by id alone and trusting
    // it would be an IDOR. Mirrors EmployeeSkillService's find-then-verify pattern.
    private EmployeeCertificate requireOwned(UUID id, UUID actorId) {
        EmployeeCertificate entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Certificate not found"));
        if (!entity.getEmployeeUserId().equals(actorId)) {
            throw new AccessDeniedException("You can only modify your own certificates");
        }
        return entity;
    }

    // Unlike EmployeeSkillService's skill/proficiency ids (always required), a category is
    // optional here — null just means "uncategorized" (see TalentDashboardService's own
    // "Uncategorized" bucket), not an error. When one IS given it must exist, but need not still
    // be active — same "editing an existing choice is never blocked" reasoning as
    // EmployeeSkillService#update's skill/level lookups.
    private CertificateCategory resolveOptionalCategory(UUID categoryId) {
        if (categoryId == null) return null;
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new NoSuchElementException("Certificate category not found: " + categoryId));
    }

    private Map<UUID, String> categoryNameMap() {
        return categoryRepository.findAll().stream().collect(Collectors.toMap(CertificateCategory::getId, CertificateCategory::getName));
    }

    private EmployeeCertificateResponse toResponse(EmployeeCertificate e, Map<UUID, String> categoryNameById) {
        return EmployeeCertificateResponse.builder()
                .id(e.getId())
                .name(e.getName())
                .categoryId(e.getCategoryId())
                .categoryName(e.getCategoryId() == null ? null : categoryNameById.get(e.getCategoryId()))
                .issuingOrganization(e.getIssuingOrganization())
                .credentialId(e.getCredentialId())
                .credentialUrl(e.getCredentialUrl())
                .issueDate(e.getIssueDate())
                .expiryDate(e.getExpiryDate())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
