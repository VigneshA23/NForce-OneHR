package com.nforce.onehr.service.talent;

import com.nforce.onehr.dto.talent.MigrationScanSummary;
import com.nforce.onehr.dto.talent.ResolveMigrationCandidateRequest;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import com.nforce.onehr.service.AuditService;
import com.nforce.onehr.service.AuditSnapshotSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillMigrationServiceTest {

    @Mock private SkillMigrationCandidateRepository candidateRepo;
    @Mock private SkillMasterRepository skillMasterRepo;
    @Mock private SkillAliasRepository skillAliasRepo;
    @Mock private EmployeeSkillRepository employeeSkillRepo;
    @Mock private ProficiencyLevelRepository proficiencyLevelRepo;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @InjectMocks private SkillMigrationService service;

    private static EmployeeSkill row(String name, String level) {
        return EmployeeSkill.builder().id(UUID.randomUUID()).employeeUserId(UUID.randomUUID())
                .skillName(name).proficiencyLevel(level).build();
    }

    @Test
    void scan_groupsByNormalizedTextAndCountsOccurrences() {
        when(employeeSkillRepo.findBySkillIdIsNull()).thenReturn(List.of(
                row("Java", "Expert"), row("  java  ", "Advanced"), row("Python", "Beginner")));
        when(skillMasterRepo.findAllByOrderByNameAsc()).thenReturn(List.of());
        when(skillAliasRepo.findAll()).thenReturn(List.of());
        when(candidateRepo.findByNormalizedText(any())).thenReturn(Optional.empty());

        MigrationScanSummary summary = service.scan();

        assertEquals(3, summary.getTotalUnmigratedRows());
        assertEquals(2, summary.getCandidatesCreated(), "'Java' and '  java  ' normalize to the same candidate");
        verify(candidateRepo, times(2)).save(argThat(c -> c.getOccurrenceCount() == 2 || c.getOccurrenceCount() == 1));
    }

    @Test
    void scan_neverRevisitsAnAlreadyResolvedCandidate() {
        when(employeeSkillRepo.findBySkillIdIsNull()).thenReturn(List.of(row("Java", "Expert")));
        when(skillMasterRepo.findAllByOrderByNameAsc()).thenReturn(List.of());
        when(skillAliasRepo.findAll()).thenReturn(List.of());
        SkillMigrationCandidate resolved = SkillMigrationCandidate.builder()
                .id(UUID.randomUUID()).normalizedText("java").status("Mapped").occurrenceCount(5).build();
        when(candidateRepo.findByNormalizedText("java")).thenReturn(Optional.of(resolved));

        MigrationScanSummary summary = service.scan();

        assertEquals(0, summary.getCandidatesCreated());
        assertEquals(0, summary.getCandidatesUpdated());
        verify(candidateRepo, never()).save(any());
        assertEquals(5, resolved.getOccurrenceCount(), "a resolved candidate's occurrence count is never overwritten by a later scan");
    }

    @Test
    void resolve_mapExisting_setsSkillIdOnEveryMatchingUnmigratedRow() {
        SkillMigrationCandidate candidate = SkillMigrationCandidate.builder()
                .id(UUID.randomUUID()).rawText("Java").normalizedText("java").status("Pending").build();
        when(candidateRepo.findById(candidate.getId())).thenReturn(Optional.of(candidate));
        when(userRepository.findByEmail(any())).thenReturn(Optional.of(User.builder().id(UUID.randomUUID()).build()));
        SkillMaster target = SkillMaster.builder().id(UUID.randomUUID()).name("Java").build();
        when(skillMasterRepo.findById(target.getId())).thenReturn(Optional.of(target));

        EmployeeSkill match = row("Java", "Expert");
        EmployeeSkill unrelated = row("Python", "Beginner");
        when(employeeSkillRepo.findBySkillIdIsNull()).thenReturn(List.of(match, unrelated));
        when(proficiencyLevelRepo.findByName("Expert")).thenReturn(Optional.of(
                ProficiencyLevel.builder().id(UUID.randomUUID()).name("Expert").build()));

        ResolveMigrationCandidateRequest req = new ResolveMigrationCandidateRequest();
        req.setAction("MAP_EXISTING");
        req.setExistingSkillId(target.getId());

        service.resolve(candidate.getId(), req, "hr@test.com");

        assertEquals(target.getId(), match.getSkillId(), "the matching row is migrated");
        assertNotNull(match.getProficiencyLevelId(), "its proficiency string resolved onto the master list too");
        assertNull(unrelated.getSkillId(), "an unrelated row is left completely untouched");
        assertEquals("Mapped", candidate.getStatus());
    }

    @Test
    void resolve_discard_touchesNoEmployeeSkillsRowsAtAll() {
        SkillMigrationCandidate candidate = SkillMigrationCandidate.builder()
                .id(UUID.randomUUID()).rawText("Nonsense").normalizedText("nonsense").status("Pending").build();
        when(candidateRepo.findById(candidate.getId())).thenReturn(Optional.of(candidate));
        when(userRepository.findByEmail(any())).thenReturn(Optional.of(User.builder().id(UUID.randomUUID()).build()));

        ResolveMigrationCandidateRequest req = new ResolveMigrationCandidateRequest();
        req.setAction("DISCARD");

        service.resolve(candidate.getId(), req, "hr@test.com");

        assertEquals("Discarded", candidate.getStatus());
        verifyNoInteractions(employeeSkillRepo);
        verifyNoInteractions(skillMasterRepo);
    }

    @Test
    void resolve_rejectsAnAlreadyResolvedCandidate() {
        SkillMigrationCandidate candidate = SkillMigrationCandidate.builder()
                .id(UUID.randomUUID()).status("Discarded").build();
        when(candidateRepo.findById(candidate.getId())).thenReturn(Optional.of(candidate));

        ResolveMigrationCandidateRequest req = new ResolveMigrationCandidateRequest();
        req.setAction("DISCARD");

        assertThrows(IllegalStateException.class, () -> service.resolve(candidate.getId(), req, "hr@test.com"));
    }
}
