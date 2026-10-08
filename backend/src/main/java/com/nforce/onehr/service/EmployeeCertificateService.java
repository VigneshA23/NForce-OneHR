package com.nforce.onehr.service;

import com.nforce.onehr.dto.certificates.EmployeeCertificateRequest;
import com.nforce.onehr.dto.certificates.EmployeeCertificateResponse;
import com.nforce.onehr.entity.EmployeeCertificate;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeCertificateRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeCertificateService {

    private final EmployeeCertificateRepository repository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<EmployeeCertificateResponse> listMine(String actorEmail) {
        UUID actorId = requireActor(actorEmail).getId();
        return repository.findByEmployeeUserIdOrderByCreatedAtAsc(actorId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public EmployeeCertificateResponse create(String actorEmail, EmployeeCertificateRequest req) {
        UUID actorId = requireActor(actorEmail).getId();
        EmployeeCertificate entity = EmployeeCertificate.builder()
                .employeeUserId(actorId)
                .name(req.getName().trim())
                .issuingOrganization(blankToNull(req.getIssuingOrganization()))
                .credentialId(blankToNull(req.getCredentialId()))
                .credentialUrl(blankToNull(req.getCredentialUrl()))
                .issueDate(req.getIssueDate())
                .expiryDate(req.getExpiryDate())
                .build();
        return toResponse(repository.save(entity));
    }

    @Transactional
    public EmployeeCertificateResponse update(String actorEmail, UUID id, EmployeeCertificateRequest req) {
        UUID actorId = requireActor(actorEmail).getId();
        EmployeeCertificate entity = requireOwned(id, actorId);
        entity.setName(req.getName().trim());
        entity.setIssuingOrganization(blankToNull(req.getIssuingOrganization()));
        entity.setCredentialId(blankToNull(req.getCredentialId()));
        entity.setCredentialUrl(blankToNull(req.getCredentialUrl()));
        entity.setIssueDate(req.getIssueDate());
        entity.setExpiryDate(req.getExpiryDate());
        return toResponse(repository.save(entity));
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

    private EmployeeCertificateResponse toResponse(EmployeeCertificate e) {
        return EmployeeCertificateResponse.builder()
                .id(e.getId())
                .name(e.getName())
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
