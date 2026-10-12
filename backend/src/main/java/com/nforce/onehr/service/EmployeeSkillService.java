package com.nforce.onehr.service;

import com.nforce.onehr.dto.skills.EmployeeSkillRequest;
import com.nforce.onehr.dto.skills.EmployeeSkillResponse;
import com.nforce.onehr.entity.EmployeeSkill;
import com.nforce.onehr.entity.ProficiencyLevel;
import com.nforce.onehr.entity.SkillMaster;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeSkillRepository;
import com.nforce.onehr.repository.ProficiencyLevelRepository;
import com.nforce.onehr.repository.SkillMasterRepository;
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
public class EmployeeSkillService {

    private final EmployeeSkillRepository repository;
    private final UserRepository userRepository;
    private final SkillMasterRepository skillMasterRepo;
    private final ProficiencyLevelRepository proficiencyLevelRepo;

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
        List<EmployeeSkill> rows = repository.findByEmployeeUserIdOrderByCreatedAtAsc(employeeUserId);
        return toResponses(rows);
    }

    // Same no-permission-check contract as listForEmployee above, for a whole team at once —
    // backs the Team Performance roster's inline skill chips without one query per employee.
    // Grouped on the raw entity (not the response) since EmployeeSkillResponse itself has no
    // employeeUserId field — it's always scoped to one employee's request elsewhere.
    @Transactional(readOnly = true)
    public Map<UUID, List<EmployeeSkillResponse>> listForEmployees(Collection<UUID> employeeUserIds) {
        if (employeeUserIds.isEmpty()) return Map.of();
        List<EmployeeSkill> rows = repository.findByEmployeeUserIdInOrderByCreatedAtAsc(employeeUserIds);
        Map<UUID, String> skillNameById = skillNameMap();
        Map<UUID, String> levelNameById = levelNameMap();
        return rows.stream().collect(Collectors.groupingBy(EmployeeSkill::getEmployeeUserId, LinkedHashMap::new,
                Collectors.mapping(e -> toResponse(e, skillNameById, levelNameById), Collectors.toList())));
    }

    @Transactional
    public EmployeeSkillResponse create(String actorEmail, EmployeeSkillRequest req) {
        UUID actorId = requireActor(actorEmail).getId();
        SkillMaster skill = requireActiveSkill(req.getSkillId());
        ProficiencyLevel level = requireActiveLevel(req.getProficiencyLevelId());
        EmployeeSkill entity = EmployeeSkill.builder()
                .employeeUserId(actorId)
                .skillId(skill.getId()).skillName(skill.getName())
                .proficiencyLevelId(level.getId()).proficiencyLevel(level.getName())
                .build();
        repository.save(entity);
        return toResponse(entity, Map.of(skill.getId(), skill.getName()), Map.of(level.getId(), level.getName()));
    }

    @Transactional
    public EmployeeSkillResponse update(String actorEmail, UUID id, EmployeeSkillRequest req) {
        UUID actorId = requireActor(actorEmail).getId();
        EmployeeSkill entity = requireOwned(id, actorId);
        // Not re-checked for "active" here (unlike create) — editing an entry that already
        // references a since-deactivated skill/level (e.g. just changing proficiency) must keep
        // working; only *picking* one for the first time is blocked. The picker UI only ever
        // offers active options anyway, so this only matters for a pre-existing row.
        SkillMaster skill = requireSkill(req.getSkillId());
        ProficiencyLevel level = requireLevel(req.getProficiencyLevelId());
        entity.setSkillId(skill.getId());
        entity.setSkillName(skill.getName());
        entity.setProficiencyLevelId(level.getId());
        entity.setProficiencyLevel(level.getName());
        repository.save(entity);
        return toResponse(entity, Map.of(skill.getId(), skill.getName()), Map.of(level.getId(), level.getName()));
    }

    @Transactional
    public void delete(String actorEmail, UUID id) {
        UUID actorId = requireActor(actorEmail).getId();
        EmployeeSkill entity = requireOwned(id, actorId);
        repository.delete(entity);
    }

    // ── Helpers ──

    private List<EmployeeSkillResponse> toResponses(List<EmployeeSkill> rows) {
        Map<UUID, String> skillNameById = skillNameMap();
        Map<UUID, String> levelNameById = levelNameMap();
        return rows.stream().map(e -> toResponse(e, skillNameById, levelNameById)).collect(Collectors.toList());
    }

    private Map<UUID, String> skillNameMap() {
        return skillMasterRepo.findAll().stream().collect(Collectors.toMap(SkillMaster::getId, SkillMaster::getName));
    }

    private Map<UUID, String> levelNameMap() {
        return proficiencyLevelRepo.findAll().stream().collect(Collectors.toMap(ProficiencyLevel::getId, ProficiencyLevel::getName));
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

    private SkillMaster requireSkill(UUID id) {
        return skillMasterRepo.findById(id).orElseThrow(() -> new NoSuchElementException("Skill not found: " + id));
    }

    private SkillMaster requireActiveSkill(UUID id) {
        SkillMaster skill = requireSkill(id);
        if (!skill.isActive()) {
            throw new IllegalArgumentException("\"" + skill.getName() + "\" is no longer an active skill — pick another one");
        }
        return skill;
    }

    private ProficiencyLevel requireLevel(UUID id) {
        return proficiencyLevelRepo.findById(id).orElseThrow(() -> new NoSuchElementException("Proficiency level not found: " + id));
    }

    private ProficiencyLevel requireActiveLevel(UUID id) {
        ProficiencyLevel level = requireLevel(id);
        if (!level.isActive()) {
            throw new IllegalArgumentException("\"" + level.getName() + "\" is no longer an active proficiency level — pick another one");
        }
        return level;
    }

    private EmployeeSkillResponse toResponse(EmployeeSkill e, Map<UUID, String> skillNameById, Map<UUID, String> levelNameById) {
        String skillName = e.getSkillId() != null ? skillNameById.getOrDefault(e.getSkillId(), e.getSkillName()) : e.getSkillName();
        String levelName = e.getProficiencyLevelId() != null
                ? levelNameById.getOrDefault(e.getProficiencyLevelId(), e.getProficiencyLevel()) : e.getProficiencyLevel();
        return EmployeeSkillResponse.builder()
                .id(e.getId())
                .skillId(e.getSkillId())
                .skillName(skillName)
                .proficiencyLevelId(e.getProficiencyLevelId())
                .proficiencyLevel(levelName)
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
