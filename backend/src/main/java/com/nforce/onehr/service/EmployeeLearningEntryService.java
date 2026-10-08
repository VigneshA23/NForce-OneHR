package com.nforce.onehr.service;

import com.nforce.onehr.dto.learning.EmployeeLearningEntryRequest;
import com.nforce.onehr.dto.learning.EmployeeLearningEntryResponse;
import com.nforce.onehr.entity.EmployeeLearningEntry;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeLearningEntryRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeLearningEntryService {

    private final EmployeeLearningEntryRepository repository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<EmployeeLearningEntryResponse> listMine(String actorEmail) {
        UUID actorId = requireActor(actorEmail).getId();
        return repository.findByEmployeeUserIdOrderByCreatedAtDesc(actorId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public EmployeeLearningEntryResponse create(String actorEmail, EmployeeLearningEntryRequest req) {
        UUID actorId = requireActor(actorEmail).getId();
        requireCompletedDateWhenCompleted(req);
        EmployeeLearningEntry entity = EmployeeLearningEntry.builder()
                .employeeUserId(actorId)
                .title(req.getTitle().trim())
                .description(trimOrNull(req.getDescription()))
                .learningType(req.getLearningType())
                .status(req.getStatus())
                .provider(trimOrNull(req.getProvider()))
                .startDate(req.getStartDate())
                .completedDate(req.getCompletedDate())
                .skillsDeveloped(toCsv(req.getSkillsDeveloped()))
                .certificateName(trimOrNull(req.getCertificateName()))
                .certificateIssueDate(req.getCertificateIssueDate())
                .certificateExpiryDate(req.getCertificateExpiryDate())
                .certificateUrl(trimOrNull(req.getCertificateUrl()))
                .verificationStatus("Self Reported")
                .notes(trimOrNull(req.getNotes()))
                .build();
        return toResponse(repository.save(entity));
    }

    @Transactional
    public EmployeeLearningEntryResponse update(String actorEmail, UUID id, EmployeeLearningEntryRequest req) {
        UUID actorId = requireActor(actorEmail).getId();
        requireCompletedDateWhenCompleted(req);
        EmployeeLearningEntry entity = requireOwned(id, actorId);
        entity.setTitle(req.getTitle().trim());
        entity.setDescription(trimOrNull(req.getDescription()));
        entity.setLearningType(req.getLearningType());
        entity.setStatus(req.getStatus());
        entity.setProvider(trimOrNull(req.getProvider()));
        entity.setStartDate(req.getStartDate());
        entity.setCompletedDate(req.getCompletedDate());
        entity.setSkillsDeveloped(toCsv(req.getSkillsDeveloped()));
        entity.setCertificateName(trimOrNull(req.getCertificateName()));
        entity.setCertificateIssueDate(req.getCertificateIssueDate());
        entity.setCertificateExpiryDate(req.getCertificateExpiryDate());
        entity.setCertificateUrl(trimOrNull(req.getCertificateUrl()));
        entity.setNotes(trimOrNull(req.getNotes()));
        return toResponse(repository.save(entity));
    }

    @Transactional
    public void delete(String actorEmail, UUID id) {
        UUID actorId = requireActor(actorEmail).getId();
        EmployeeLearningEntry entity = requireOwned(id, actorId);
        repository.delete(entity);
    }

    private void requireCompletedDateWhenCompleted(EmployeeLearningEntryRequest req) {
        if ("Completed".equals(req.getStatus()) && req.getCompletedDate() == null) {
            throw new IllegalArgumentException("Completion date is required when status is Completed");
        }
    }

    // Never trusts a client-supplied employeeId — the acting employee is always resolved from the
    // authenticated principal's email, matching ProfileService's own requireUser pattern.
    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    // Ownership check: {id} in the URL is attacker-controlled — loading by id alone and trusting
    // it would be an IDOR. Mirrors EmployeeEducationService's find-then-verify pattern.
    private EmployeeLearningEntry requireOwned(UUID id, UUID actorId) {
        EmployeeLearningEntry entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Learning entry not found"));
        if (!entity.getEmployeeUserId().equals(actorId)) {
            throw new AccessDeniedException("You can only modify your own learning entries");
        }
        return entity;
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // Skills developed are stored as a plain comma-separated column (same convention as
    // ExpenseClaim.approvalStages via ApprovalRuleEvaluationService.toCsv) rather than a join
    // table: lightweight tags picked from the employee's own skill list, not a referential
    // integrity requirement.
    private static String toCsv(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        return values.stream()
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .collect(Collectors.joining(","));
    }

    private static List<String> fromCsv(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptyList();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .collect(Collectors.toList());
    }

    private EmployeeLearningEntryResponse toResponse(EmployeeLearningEntry e) {
        return EmployeeLearningEntryResponse.builder()
                .id(e.getId())
                .title(e.getTitle())
                .description(e.getDescription())
                .learningType(e.getLearningType())
                .status(e.getStatus())
                .provider(e.getProvider())
                .startDate(e.getStartDate())
                .completedDate(e.getCompletedDate())
                .skillsDeveloped(fromCsv(e.getSkillsDeveloped()))
                .certificateName(e.getCertificateName())
                .certificateIssueDate(e.getCertificateIssueDate())
                .certificateExpiryDate(e.getCertificateExpiryDate())
                .certificateUrl(e.getCertificateUrl())
                .verificationStatus(e.getVerificationStatus())
                .notes(e.getNotes())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
