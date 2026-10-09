package com.nforce.onehr.service.talent;

import com.nforce.onehr.dto.talent.ProficiencyLevelResponse;
import com.nforce.onehr.dto.talent.ReorderProficiencyLevelsRequest;
import com.nforce.onehr.dto.talent.UpsertProficiencyLevelRequest;
import com.nforce.onehr.entity.ProficiencyLevel;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeSkillRepository;
import com.nforce.onehr.repository.ProficiencyLevelRepository;
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
 * Settings > Proficiency Levels (org.talent.configure). Rename/reorder are always safe; adding is
 * always safe (an unused level breaks nothing); deactivating/removing is BLOCKED outright if any
 * employee_skills row currently references it — the spec allows that or a remapping step, and a
 * hard block is the simpler, safer v1 (an admin can always re-point affected rows' proficiency
 * manually first, same as DocumentTypeService's own delete-blocked-by-usage precedent).
 */
@Service
@RequiredArgsConstructor
public class ProficiencyLevelService {

    private final ProficiencyLevelRepository proficiencyLevelRepo;
    private final EmployeeSkillRepository employeeSkillRepo;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional(readOnly = true)
    public List<ProficiencyLevelResponse> listAll() {
        return proficiencyLevelRepo.findAllByOrderBySortOrderAsc().stream()
                .map(p -> ProficiencyLevelResponse.from(p, employeeSkillRepo.countByProficiencyLevelId(p.getId())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ProficiencyLevelResponse> listActive() {
        return proficiencyLevelRepo.findByActiveTrueOrderBySortOrderAsc().stream()
                .map(p -> ProficiencyLevelResponse.from(p, employeeSkillRepo.countByProficiencyLevelId(p.getId())))
                .collect(Collectors.toList());
    }

    @Transactional
    public ProficiencyLevelResponse create(UpsertProficiencyLevelRequest req, String actorEmail) {
        int nextOrder = proficiencyLevelRepo.findAllByOrderBySortOrderAsc().stream()
                .mapToInt(ProficiencyLevel::getSortOrder).max().orElse(0) + 1;
        ProficiencyLevel level = ProficiencyLevel.builder()
                .name(requireName(req.getName())).description(req.getDescription()).sortOrder(nextOrder).build();
        saveOrRejectDuplicate(level);
        auditService.log(actorId(actorEmail), "PROFICIENCY_LEVEL_CREATED", level.getId(),
                null, auditSnapshot.toJson(Map.of("name", level.getName())));
        return ProficiencyLevelResponse.from(level, 0);
    }

    @Transactional
    public ProficiencyLevelResponse rename(UUID id, UpsertProficiencyLevelRequest req, String actorEmail) {
        ProficiencyLevel level = requireLevel(id);
        String before = level.getName();
        level.setName(requireName(req.getName()));
        level.setDescription(req.getDescription());
        saveOrRejectDuplicate(level);
        auditService.log(actorId(actorEmail), "PROFICIENCY_LEVEL_UPDATED", level.getId(),
                auditSnapshot.toJson(Map.of("name", before)), auditSnapshot.toJson(Map.of("name", level.getName())));
        return ProficiencyLevelResponse.from(level, employeeSkillRepo.countByProficiencyLevelId(id));
    }

    @Transactional
    public List<ProficiencyLevelResponse> reorder(ReorderProficiencyLevelsRequest req) {
        List<ProficiencyLevel> all = proficiencyLevelRepo.findAllByOrderBySortOrderAsc();
        if (req.getOrderedIds().size() != all.size()
                || !req.getOrderedIds().containsAll(all.stream().map(ProficiencyLevel::getId).toList())) {
            throw new IllegalArgumentException("Reorder must include every existing proficiency level exactly once");
        }
        Map<UUID, ProficiencyLevel> byId = all.stream().collect(Collectors.toMap(ProficiencyLevel::getId, p -> p));
        for (int i = 0; i < req.getOrderedIds().size(); i++) {
            byId.get(req.getOrderedIds().get(i)).setSortOrder(i + 1);
        }
        proficiencyLevelRepo.saveAll(byId.values());
        return listAll();
    }

    @Transactional
    public void deactivate(UUID id, String actorEmail) {
        ProficiencyLevel level = requireLevel(id);
        long usage = employeeSkillRepo.countByProficiencyLevelId(id);
        if (usage > 0) {
            throw new IllegalStateException("Cannot deactivate \"" + level.getName() + "\": " + usage
                    + " employee skill record(s) currently use it. Re-assign them to another level first.");
        }
        level.setActive(false);
        proficiencyLevelRepo.save(level);
        auditService.log(actorId(actorEmail), "PROFICIENCY_LEVEL_DEACTIVATED", level.getId(),
                null, auditSnapshot.toJson(Map.of("name", level.getName())));
    }

    @Transactional
    public void reactivate(UUID id, String actorEmail) {
        ProficiencyLevel level = requireLevel(id);
        level.setActive(true);
        proficiencyLevelRepo.save(level);
        auditService.log(actorId(actorEmail), "PROFICIENCY_LEVEL_REACTIVATED", level.getId(),
                null, auditSnapshot.toJson(Map.of("name", level.getName())));
    }

    // ── Helpers ──

    private static String requireName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Proficiency level name is required");
        if (name.length() > 50) throw new IllegalArgumentException("Proficiency level name must be at most 50 characters");
        return name;
    }

    private void saveOrRejectDuplicate(ProficiencyLevel level) {
        try {
            proficiencyLevelRepo.saveAndFlush(level);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("Proficiency level \"" + level.getName() + "\" already exists");
        }
    }

    private ProficiencyLevel requireLevel(UUID id) {
        return proficiencyLevelRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Proficiency level not found: " + id));
    }

    private UUID actorId(String actorEmail) {
        return userRepository.findByEmail(actorEmail).map(User::getId).orElse(null);
    }
}
