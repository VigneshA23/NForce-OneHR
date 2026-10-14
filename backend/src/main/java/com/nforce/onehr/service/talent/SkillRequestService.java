package com.nforce.onehr.service.talent;

import com.nforce.onehr.dto.talent.CreateSkillRequestRequest;
import com.nforce.onehr.dto.talent.SkillRequestResponse;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import com.nforce.onehr.service.AuditService;
import com.nforce.onehr.service.AuditSnapshotSerializer;
import com.nforce.onehr.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Employee Skills tab's "Request new skill" flow, and HR/Super Admin's review queue for it
 * (org.talent.configure). Approving or mapping a request both end the same way — the resulting
 * skill_master row (new, for Approve; existing, for Map) is attached to the REQUESTER's own
 * employee_skills, same as if they'd picked it from the autocomplete themselves — at the lowest
 * active proficiency level by default, since the request flow never asks for one; the employee
 * can change it afterward like any other skill entry.
 */
@Service
@RequiredArgsConstructor
public class SkillRequestService {

    private final SkillRequestRepository skillRequestRepo;
    private final SkillMasterRepository skillMasterRepo;
    private final EmployeeSkillRepository employeeSkillRepo;
    private final ProficiencyLevelRepository proficiencyLevelRepo;
    private final EmployeeRepository employeeRepo;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final NotificationService notificationService;

    @Transactional
    public SkillRequestResponse submit(CreateSkillRequestRequest req, String actorEmail) {
        User actor = requireUser(actorEmail);
        SkillRequest request = SkillRequest.builder()
                .requestedByUserId(actor.getId())
                .name(requireName(req.getName()))
                .category(blankToNull(req.getCategory()))
                .note(blankToNull(req.getNote()))
                .build();
        skillRequestRepo.save(request);
        // Self-submitted — intentionally not audit-logged, same convention as
        // LEAVE_REQUEST_SUBMITTED/ASSET_REQUEST_SUBMITTED/EXPENSE_SUBMITTED (see AuditActionCategory).

        // HR has no other way to learn a new request exists short of manually opening the Skill
        // Requests queue — notify every active HR Admin, same pattern as Regularization's
        // *_SUBMITTED notify (Super Admin already has blanket queue visibility, so isn't paged too).
        String requesterName = employeeRepo.findById(actor.getId())
                .map(Employee::getFullName)
                .orElse(actor.getEmail());
        Set<UUID> recipients = userRepository.findActiveHrAdminUserIds();
        recipients.stream().filter(Objects::nonNull).forEach(id -> notificationService.send(id,
                "SKILL_REQUEST_SUBMITTED", "New Skill Request",
                requesterName + " has requested a new skill: " + request.getName() + ".",
                "/talent-performance?tab=settings&sub=requests"));

        return toResponse(request);
    }

    @Transactional(readOnly = true)
    public List<SkillRequestResponse> listMine(String actorEmail) {
        User actor = requireUser(actorEmail);
        return skillRequestRepo.findByRequestedByUserIdOrderByCreatedAtDesc(actor.getId()).stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<SkillRequestResponse> listPending() {
        return skillRequestRepo.findByStatusOrderByCreatedAtAsc("Pending").stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional
    public SkillRequestResponse approve(UUID requestId, String actorEmail) {
        SkillRequest request = requirePendingRequest(requestId);
        SkillMaster skill = SkillMaster.builder().name(request.getName()).category(request.getCategory()).build();
        skillMasterRepo.save(skill);
        attachSkillToRequester(request, skill);
        return resolveRequest(request, "Approved", skill.getId(), actorEmail, "SKILL_REQUEST_APPROVED");
    }

    @Transactional
    public SkillRequestResponse mapToExisting(UUID requestId, UUID existingSkillId, String actorEmail) {
        SkillRequest request = requirePendingRequest(requestId);
        SkillMaster skill = skillMasterRepo.findById(existingSkillId)
                .orElseThrow(() -> new NoSuchElementException("Skill not found: " + existingSkillId));
        attachSkillToRequester(request, skill);
        return resolveRequest(request, "Mapped", skill.getId(), actorEmail, "SKILL_REQUEST_MAPPED");
    }

    @Transactional
    public SkillRequestResponse reject(UUID requestId, String actorEmail) {
        SkillRequest request = requirePendingRequest(requestId);
        return resolveRequest(request, "Rejected", null, actorEmail, "SKILL_REQUEST_REJECTED");
    }

    // ── Helpers ──

    private void attachSkillToRequester(SkillRequest request, SkillMaster skill) {
        boolean alreadyHasIt = employeeSkillRepo.findByEmployeeUserIdOrderByCreatedAtAsc(request.getRequestedByUserId())
                .stream().anyMatch(s -> skill.getId().equals(s.getSkillId()));
        if (alreadyHasIt) return;
        ProficiencyLevel defaultLevel = proficiencyLevelRepo.findByActiveTrueOrderBySortOrderAsc().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No active proficiency levels configured"));
        employeeSkillRepo.save(EmployeeSkill.builder()
                .employeeUserId(request.getRequestedByUserId())
                .skillName(skill.getName())
                .proficiencyLevel(defaultLevel.getName())
                .skillId(skill.getId())
                .proficiencyLevelId(defaultLevel.getId())
                .build());
    }

    private SkillRequestResponse resolveRequest(SkillRequest request, String status, UUID mappedSkillId,
                                                 String actorEmail, String auditAction) {
        User reviewer = requireUser(actorEmail);
        request.setStatus(status);
        request.setMappedToSkillId(mappedSkillId);
        request.setReviewedBy(reviewer.getId());
        request.setReviewedAt(Instant.now());
        skillRequestRepo.save(request);
        auditService.log(reviewer.getId(), auditAction, request.getId(),
                auditSnapshot.toJson(Map.of("status", "Pending")), auditSnapshot.toJson(Map.of("status", status)));
        return toResponse(request);
    }

    private SkillRequestResponse toResponse(SkillRequest r) {
        String requesterName = employeeRepo.findById(r.getRequestedByUserId())
                .map(Employee::getFullName)
                .orElseGet(() -> userRepository.findById(r.getRequestedByUserId()).map(User::getEmail).orElse("Unknown"));
        String mappedSkillName = r.getMappedToSkillId() == null ? null
                : skillMasterRepo.findById(r.getMappedToSkillId()).map(SkillMaster::getName).orElse(null);
        return SkillRequestResponse.from(r, requesterName, mappedSkillName);
    }

    private SkillRequest requirePendingRequest(UUID id) {
        SkillRequest request = skillRequestRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Skill request not found: " + id));
        if (!"Pending".equals(request.getStatus())) {
            throw new IllegalStateException("This request has already been reviewed (" + request.getStatus() + ")");
        }
        return request;
    }

    private static String requireName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Skill name is required");
        if (name.length() > 100) throw new IllegalArgumentException("Skill name must be at most 100 characters");
        return name;
    }

    private static String blankToNull(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("User not found: " + email));
    }
}
