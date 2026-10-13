package com.nforce.onehr.service.talent;

import com.nforce.onehr.dto.talent.CreateSkillMasterRequest;
import com.nforce.onehr.dto.talent.MergeSkillsRequest;
import com.nforce.onehr.dto.talent.SkillMasterResponse;
import com.nforce.onehr.dto.talent.UpdateSkillMasterRequest;
import com.nforce.onehr.entity.EmployeeSkill;
import com.nforce.onehr.entity.SkillAlias;
import com.nforce.onehr.entity.SkillMaster;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeSkillRepository;
import com.nforce.onehr.repository.SkillAliasRepository;
import com.nforce.onehr.repository.SkillMasterRepository;
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

/**
 * Settings > Skills (org.talent.configure — enforced at SkillMasterController via
 * {@code @PreAuthorize}, identical for HR_ADMIN and SUPER_ADMIN, same convention as
 * DocumentTypeController). Never hard-deletes a skill in use — merge or deactivate instead, per
 * the Talent & Skills spec.
 */
@Service
@RequiredArgsConstructor
public class SkillMasterService {

    private final SkillMasterRepository skillMasterRepo;
    private final SkillAliasRepository skillAliasRepo;
    private final EmployeeSkillRepository employeeSkillRepo;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional(readOnly = true)
    public List<SkillMasterResponse> listAll() {
        return skillMasterRepo.findAllByOrderByNameAsc().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<SkillMasterResponse> listActive() {
        return skillMasterRepo.findByActiveTrueOrderByNameAsc().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public SkillMasterResponse create(CreateSkillMasterRequest req, String actorEmail) {
        String name = requireName(req.getName());
        SkillMaster skill = SkillMaster.builder().name(name).category(blankToNull(req.getCategory())).build();
        saveOrRejectDuplicate(skill);
        if (req.getAliases() != null) {
            req.getAliases().forEach(a -> addAliasQuietly(skill.getId(), a));
        }
        auditService.log(actorId(actorEmail), "SKILL_MASTER_CREATED", skill.getId(),
                null, auditSnapshot.toJson(Map.of("name", name)));
        return toResponse(skill);
    }

    @Transactional
    public SkillMasterResponse update(UUID id, UpdateSkillMasterRequest req, String actorEmail) {
        SkillMaster skill = requireSkill(id);
        String before = skill.getName();
        if (req.getName() != null) skill.setName(requireName(req.getName()));
        if (req.getCategory() != null) skill.setCategory(blankToNull(req.getCategory()));
        saveOrRejectDuplicate(skill);
        auditService.log(actorId(actorEmail), "SKILL_MASTER_UPDATED", skill.getId(),
                auditSnapshot.toJson(Map.of("name", before)), auditSnapshot.toJson(Map.of("name", skill.getName())));
        return toResponse(skill);
    }

    @Transactional
    public void setActive(UUID id, boolean active, String actorEmail) {
        SkillMaster skill = requireSkill(id);
        skill.setActive(active);
        skillMasterRepo.save(skill);
        auditService.log(actorId(actorEmail), active ? "SKILL_MASTER_REACTIVATED" : "SKILL_MASTER_DEACTIVATED",
                skill.getId(), null, auditSnapshot.toJson(Map.of("name", skill.getName())));
    }

    @Transactional
    public SkillMasterResponse addAlias(UUID skillId, String alias) {
        SkillMaster skill = requireSkill(skillId);
        addAliasQuietly(skill.getId(), alias);
        return toResponse(skill);
    }

    @Transactional
    public void removeAlias(UUID skillId, UUID aliasId) {
        SkillAlias alias = skillAliasRepo.findById(aliasId)
                .orElseThrow(() -> new NoSuchElementException("Alias not found: " + aliasId));
        if (!alias.getSkillId().equals(skillId)) {
            throw new IllegalArgumentException("That alias does not belong to this skill");
        }
        skillAliasRepo.delete(alias);
    }

    /**
     * Repoints every employee_skills row and skill_alias off {@code sourceSkillId} onto
     * {@code targetSkillId}, keeps the source's own name as a new alias of the target (so a
     * search/autocomplete for the old name still resolves correctly), then deactivates — never
     * deletes — the source. Idempotent: merging an already-deactivated/aliased source again is
     * safe (repointing rows that already point at the target is a no-op).
     */
    @Transactional
    public SkillMasterResponse merge(MergeSkillsRequest req, String actorEmail) {
        if (req.getSourceSkillId().equals(req.getTargetSkillId())) {
            throw new IllegalArgumentException("Cannot merge a skill into itself");
        }
        SkillMaster source = requireSkill(req.getSourceSkillId());
        SkillMaster target = requireSkill(req.getTargetSkillId());

        List<EmployeeSkill> rows = employeeSkillRepo.findBySkillId(source.getId());
        rows.forEach(r -> r.setSkillId(target.getId()));
        employeeSkillRepo.saveAll(rows);

        List<SkillAlias> aliases = skillAliasRepo.findBySkillId(source.getId());
        aliases.forEach(a -> a.setSkillId(target.getId()));
        skillAliasRepo.saveAll(aliases);

        addAliasQuietly(target.getId(), source.getName());

        source.setActive(false);
        skillMasterRepo.save(source);

        auditService.log(actorId(actorEmail), "SKILL_MASTER_MERGED", target.getId(),
                auditSnapshot.toJson(Map.of("mergedFrom", source.getName())),
                auditSnapshot.toJson(Map.of("mergedInto", target.getName())));
        return toResponse(target);
    }

    // ── Helpers ──

    private void addAliasQuietly(UUID skillId, String rawAlias) {
        String alias = rawAlias == null ? "" : rawAlias.trim();
        if (alias.isEmpty()) return;
        String normalized = SkillTextNormalizer.normalize(alias);
        if (normalized.isEmpty() || skillAliasRepo.findByNormalizedAlias(normalized).isPresent()) return;
        skillAliasRepo.save(SkillAlias.builder().skillId(skillId).alias(alias).normalizedAlias(normalized).build());
    }

    private SkillMasterResponse toResponse(SkillMaster skill) {
        List<String> aliases = skillAliasRepo.findBySkillId(skill.getId()).stream()
                .map(SkillAlias::getAlias)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
        long usage = employeeSkillRepo.countBySkillId(skill.getId());
        return SkillMasterResponse.from(skill, aliases, usage);
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

    private void saveOrRejectDuplicate(SkillMaster skill) {
        try {
            skillMasterRepo.saveAndFlush(skill);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("Skill \"" + skill.getName() + "\" already exists");
        }
    }

    private SkillMaster requireSkill(UUID id) {
        return skillMasterRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Skill not found: " + id));
    }

    private UUID actorId(String actorEmail) {
        return userRepository.findByEmail(actorEmail).map(User::getId).orElse(null);
    }
}
