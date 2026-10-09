package com.nforce.onehr.service.talent;

import com.nforce.onehr.dto.talent.MigrationScanSummary;
import com.nforce.onehr.dto.talent.ResolveMigrationCandidateRequest;
import com.nforce.onehr.dto.talent.SkillMigrationCandidateResponse;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import com.nforce.onehr.service.AuditService;
import com.nforce.onehr.service.AuditSnapshotSerializer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The one-time free-text-skill migration's scan + Settings > Migration Review screen. Scanning
 * (see {@link #scan()}) is idempotent and safe to re-run at any time: it only ever creates a
 * candidate for a normalized value that doesn't have one yet, or refreshes one still Pending —
 * a candidate HR has already resolved (Mapped/NewSkillCreated/Discarded) is never revisited or
 * reset, even if the scan runs again later and the underlying occurrence count would differ.
 *
 * <p>employee_skills.skill_name/proficiency_level (the legacy free-text columns) are never
 * dropped or cleared by any of this — resolving a candidate only ever ADDS skill_id (and,
 * best-effort, proficiency_level_id) onto the matching rows; see V219's own header comment for
 * why that's deliberate.
 */
@Service
@RequiredArgsConstructor
public class SkillMigrationService {

    private final SkillMigrationCandidateRepository candidateRepo;
    private final SkillMasterRepository skillMasterRepo;
    private final SkillAliasRepository skillAliasRepo;
    private final EmployeeSkillRepository employeeSkillRepo;
    private final ProficiencyLevelRepository proficiencyLevelRepo;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional
    public MigrationScanSummary scan() {
        List<EmployeeSkill> unmigrated = employeeSkillRepo.findBySkillIdIsNull();
        Map<String, List<EmployeeSkill>> byNormalized = unmigrated.stream()
                .collect(Collectors.groupingBy(s -> SkillTextNormalizer.normalize(s.getSkillName())));

        // Built once up front, reused for every group below — avoids re-querying the whole skill
        // master list once per distinct normalized value.
        Map<String, UUID> nameToSkillId = skillMasterRepo.findAllByOrderByNameAsc().stream()
                .collect(Collectors.toMap(s -> SkillTextNormalizer.normalize(s.getName()), SkillMaster::getId, (a, b) -> a));
        Map<String, UUID> aliasToSkillId = skillAliasRepo.findAll().stream()
                .collect(Collectors.toMap(SkillAlias::getNormalizedAlias, SkillAlias::getSkillId, (a, b) -> a));

        int created = 0, updated = 0, autoMatched = 0;
        for (Map.Entry<String, List<EmployeeSkill>> entry : byNormalized.entrySet()) {
            String normalized = entry.getKey();
            if (normalized.isEmpty()) continue;
            List<EmployeeSkill> rows = entry.getValue();

            UUID proposedSkillId = aliasToSkillId.getOrDefault(normalized, nameToSkillId.get(normalized));
            if (proposedSkillId != null) autoMatched++;

            Optional<SkillMigrationCandidate> existing = candidateRepo.findByNormalizedText(normalized);
            if (existing.isPresent()) {
                SkillMigrationCandidate candidate = existing.get();
                if (!"Pending".equals(candidate.getStatus())) continue; // already resolved — never revisited
                candidate.setOccurrenceCount(rows.size());
                candidate.setProposedSkillId(proposedSkillId);
                candidateRepo.save(candidate);
                updated++;
            } else {
                candidateRepo.save(SkillMigrationCandidate.builder()
                        .rawText(rows.get(0).getSkillName())
                        .normalizedText(normalized)
                        .occurrenceCount(rows.size())
                        .proposedSkillId(proposedSkillId)
                        .build());
                created++;
            }
        }
        return new MigrationScanSummary(unmigrated.size(), created, updated, autoMatched);
    }

    @Transactional(readOnly = true)
    public List<SkillMigrationCandidateResponse> listPending() {
        return candidateRepo.findByStatusOrderByOccurrenceCountDesc("Pending").stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<SkillMigrationCandidateResponse> listAll() {
        return candidateRepo.findAll().stream()
                .sorted((a, b) -> Integer.compare(b.getOccurrenceCount(), a.getOccurrenceCount()))
                .map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional
    public SkillMigrationCandidateResponse resolve(UUID candidateId, ResolveMigrationCandidateRequest req, String actorEmail) {
        SkillMigrationCandidate candidate = requirePendingCandidate(candidateId);
        User reviewer = requireUser(actorEmail);

        switch (req.getAction()) {
            case "DISCARD" -> candidate.setStatus("Discarded");
            case "MAP_EXISTING" -> {
                if (req.getExistingSkillId() == null) {
                    throw new IllegalArgumentException("existingSkillId is required for MAP_EXISTING");
                }
                SkillMaster target = skillMasterRepo.findById(req.getExistingSkillId())
                        .orElseThrow(() -> new NoSuchElementException("Skill not found: " + req.getExistingSkillId()));
                applyToUnmigratedRows(candidate.getNormalizedText(), target);
                candidate.setResolvedSkillId(target.getId());
                candidate.setStatus("Mapped");
            }
            case "CREATE_NEW" -> {
                SkillMaster created = SkillMaster.builder()
                        .name(candidate.getRawText()).category(blankToNull(req.getCategory())).build();
                skillMasterRepo.save(created);
                applyToUnmigratedRows(candidate.getNormalizedText(), created);
                candidate.setResolvedSkillId(created.getId());
                candidate.setStatus("NewSkillCreated");
            }
            default -> throw new IllegalArgumentException("Unknown action: " + req.getAction());
        }
        candidate.setReviewedBy(reviewer.getId());
        candidate.setReviewedAt(Instant.now());
        candidateRepo.save(candidate);

        auditService.log(reviewer.getId(), "SKILL_MIGRATION_RESOLVED", candidate.getId(),
                auditSnapshot.toJson(Map.of("normalizedText", candidate.getNormalizedText())),
                auditSnapshot.toJson(Map.of("status", candidate.getStatus())));
        return toResponse(candidate);
    }

    // ── Helpers ──

    private void applyToUnmigratedRows(String normalizedText, SkillMaster target) {
        List<EmployeeSkill> rows = employeeSkillRepo.findBySkillIdIsNull().stream()
                .filter(s -> SkillTextNormalizer.normalize(s.getSkillName()).equals(normalizedText))
                .collect(Collectors.toList());
        for (EmployeeSkill row : rows) {
            row.setSkillId(target.getId());
            // Best-effort: also resolve the legacy proficiency_level string onto the master list
            // where it matches exactly (CITEXT, case-insensitive) — a non-match just leaves
            // proficiency_level_id null, same "no destructive assumption" rule as everywhere else
            // in this migration.
            proficiencyLevelRepo.findByName(row.getProficiencyLevel())
                    .ifPresent(level -> row.setProficiencyLevelId(level.getId()));
        }
        employeeSkillRepo.saveAll(rows);
    }

    private SkillMigrationCandidateResponse toResponse(SkillMigrationCandidate c) {
        String proposedName = c.getProposedSkillId() == null ? null
                : skillMasterRepo.findById(c.getProposedSkillId()).map(SkillMaster::getName).orElse(null);
        String resolvedName = c.getResolvedSkillId() == null ? null
                : skillMasterRepo.findById(c.getResolvedSkillId()).map(SkillMaster::getName).orElse(null);
        return SkillMigrationCandidateResponse.from(c, proposedName, resolvedName);
    }

    private SkillMigrationCandidate requirePendingCandidate(UUID id) {
        SkillMigrationCandidate candidate = candidateRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Migration candidate not found: " + id));
        if (!"Pending".equals(candidate.getStatus())) {
            throw new IllegalStateException("This candidate has already been resolved (" + candidate.getStatus() + ")");
        }
        return candidate;
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
