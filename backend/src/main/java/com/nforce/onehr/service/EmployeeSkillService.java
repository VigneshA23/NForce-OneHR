package com.nforce.onehr.service;

import com.nforce.onehr.dto.skills.EmployeeSkillRequest;
import com.nforce.onehr.dto.skills.EmployeeSkillResponse;
import com.nforce.onehr.entity.EmployeeSkill;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeSkillRepository;
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
public class EmployeeSkillService {

    private final EmployeeSkillRepository repository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<EmployeeSkillResponse> listMine(String actorEmail) {
        UUID actorId = requireActor(actorEmail).getId();
        return listForEmployee(actorId);
    }

    // Caller must already have verified it's entitled to see this employee's skills (e.g. via
    // DirectReportScopeService#isDirectReport) — this does no permission check of its own, unlike
    // listMine above, which only ever resolves the caller's own id.
    @Transactional(readOnly = true)
    public List<EmployeeSkillResponse> listForEmployee(UUID employeeUserId) {
        return repository.findByEmployeeUserIdOrderByCreatedAtAsc(employeeUserId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // Same no-permission-check contract as listForEmployee above, for a whole team at once —
    // backs the Team Performance roster's inline skill chips without one query per employee.
    // Grouped on the raw entity (not the response) since EmployeeSkillResponse itself has no
    // employeeUserId field — it's always scoped to one employee's request elsewhere.
    @Transactional(readOnly = true)
    public java.util.Map<UUID, List<EmployeeSkillResponse>> listForEmployees(java.util.Collection<UUID> employeeUserIds) {
        if (employeeUserIds.isEmpty()) return java.util.Map.of();
        return repository.findByEmployeeUserIdInOrderByCreatedAtAsc(employeeUserIds).stream()
                .collect(Collectors.groupingBy(EmployeeSkill::getEmployeeUserId, java.util.LinkedHashMap::new,
                        Collectors.mapping(this::toResponse, Collectors.toList())));
    }

    @Transactional
    public EmployeeSkillResponse create(String actorEmail, EmployeeSkillRequest req) {
        UUID actorId = requireActor(actorEmail).getId();
        EmployeeSkill entity = EmployeeSkill.builder()
                .employeeUserId(actorId)
                .skillName(req.getSkillName().trim())
                .proficiencyLevel(req.getProficiencyLevel())
                .build();
        return toResponse(repository.save(entity));
    }

    @Transactional
    public EmployeeSkillResponse update(String actorEmail, UUID id, EmployeeSkillRequest req) {
        UUID actorId = requireActor(actorEmail).getId();
        EmployeeSkill entity = requireOwned(id, actorId);
        entity.setSkillName(req.getSkillName().trim());
        entity.setProficiencyLevel(req.getProficiencyLevel());
        return toResponse(repository.save(entity));
    }

    @Transactional
    public void delete(String actorEmail, UUID id) {
        UUID actorId = requireActor(actorEmail).getId();
        EmployeeSkill entity = requireOwned(id, actorId);
        repository.delete(entity);
    }

    // Never trusts a client-supplied employeeId — the acting employee is always resolved from the
    // authenticated principal's email, matching ProfileService's own requireUser pattern.
    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    // Ownership check: {id} in the URL is attacker-controlled — loading by id alone and trusting
    // it would be an IDOR. Mirrors EmployeeEducationService's find-then-verify pattern.
    private EmployeeSkill requireOwned(UUID id, UUID actorId) {
        EmployeeSkill entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found"));
        if (!entity.getEmployeeUserId().equals(actorId)) {
            throw new AccessDeniedException("You can only modify your own skills");
        }
        return entity;
    }

    private EmployeeSkillResponse toResponse(EmployeeSkill e) {
        return EmployeeSkillResponse.builder()
                .id(e.getId())
                .skillName(e.getSkillName())
                .proficiencyLevel(e.getProficiencyLevel())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
