package com.nforce.onehr.service.talent;

import com.nforce.onehr.dto.talent.CreateSkillMasterRequest;
import com.nforce.onehr.dto.talent.MergeSkillsRequest;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillMasterServiceTest {

    @Mock private SkillMasterRepository skillMasterRepo;
    @Mock private SkillAliasRepository skillAliasRepo;
    @Mock private EmployeeSkillRepository employeeSkillRepo;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @InjectMocks private SkillMasterService service;

    private static SkillMaster skill(String name) {
        return SkillMaster.builder().id(UUID.randomUUID()).name(name).active(true).build();
    }

    @Test
    void create_rejectsDuplicateNameFromDbConstraint() {
        CreateSkillMasterRequest req = new CreateSkillMasterRequest();
        req.setName("Java");
        when(skillMasterRepo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));

        assertThrows(IllegalStateException.class, () -> service.create(req, "actor@test.com"));
    }

    @Test
    void create_seedsAliasesAndSkipsBlankOnes() {
        CreateSkillMasterRequest req = new CreateSkillMasterRequest();
        req.setName("Java");
        req.setAliases(List.of("Core Java", "  ", "JDK"));
        when(skillMasterRepo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(skillAliasRepo.findByNormalizedAlias(any())).thenReturn(Optional.empty());
        when(skillAliasRepo.findBySkillId(any())).thenReturn(List.of());
        when(employeeSkillRepo.countBySkillId(any())).thenReturn(0L);
        when(userRepository.findByEmail("actor@test.com")).thenReturn(Optional.of(User.builder().id(UUID.randomUUID()).build()));

        service.create(req, "actor@test.com");

        // Exactly 2 real aliases saved — the blank one is silently skipped, not stored as noise.
        verify(skillAliasRepo, times(2)).save(any());
    }

    @Test
    void merge_repointsEmployeeSkillsAndAliasesThenDeactivatesSource() {
        SkillMaster source = skill("Core Java");
        SkillMaster target = skill("Java");
        MergeSkillsRequest req = new MergeSkillsRequest();
        req.setSourceSkillId(source.getId());
        req.setTargetSkillId(target.getId());

        when(skillMasterRepo.findById(source.getId())).thenReturn(Optional.of(source));
        when(skillMasterRepo.findById(target.getId())).thenReturn(Optional.of(target));
        EmployeeSkill row = EmployeeSkill.builder().id(UUID.randomUUID()).skillId(source.getId()).build();
        when(employeeSkillRepo.findBySkillId(source.getId())).thenReturn(List.of(row));
        SkillAlias alias = SkillAlias.builder().id(UUID.randomUUID()).skillId(source.getId()).build();
        when(skillAliasRepo.findBySkillId(source.getId())).thenReturn(List.of(alias));
        when(skillAliasRepo.findByNormalizedAlias(any())).thenReturn(Optional.empty());
        // toResponse(target) at the end calls findBySkillId(target.getId()) too
        when(skillAliasRepo.findBySkillId(target.getId())).thenReturn(List.of());
        when(employeeSkillRepo.countBySkillId(target.getId())).thenReturn(0L);
        when(userRepository.findByEmail(any())).thenReturn(Optional.of(User.builder().id(UUID.randomUUID()).build()));

        service.merge(req, "actor@test.com");

        assertEquals(target.getId(), row.getSkillId(), "employee_skills row repointed at the target");
        assertEquals(target.getId(), alias.getSkillId(), "pre-existing alias repointed at the target");
        assertFalse(source.isActive(), "source deactivated, not deleted");
        verify(skillMasterRepo, never()).delete(any());
        // The source's own name becomes a new alias of the target so old searches still resolve.
        verify(skillAliasRepo).save(argThat(a -> a.getSkillId().equals(target.getId()) && a.getAlias().equals("Core Java")));
    }

    @Test
    void merge_rejectsMergingASkillIntoItself() {
        UUID id = UUID.randomUUID();
        MergeSkillsRequest req = new MergeSkillsRequest();
        req.setSourceSkillId(id);
        req.setTargetSkillId(id);

        assertThrows(IllegalArgumentException.class, () -> service.merge(req, "actor@test.com"));
        verifyNoInteractions(skillMasterRepo);
    }
}
