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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeeSkillServiceTest {

    @Mock private EmployeeSkillRepository repository;
    @Mock private UserRepository userRepository;
    @Mock private SkillMasterRepository skillMasterRepo;
    @Mock private ProficiencyLevelRepository proficiencyLevelRepo;
    @InjectMocks private EmployeeSkillService service;

    private static final UUID ACTOR_ID = UUID.randomUUID();

    private EmployeeSkillRequest req(UUID skillId, UUID levelId) {
        EmployeeSkillRequest r = new EmployeeSkillRequest();
        r.setSkillId(skillId);
        r.setProficiencyLevelId(levelId);
        return r;
    }

    @Test
    void create_resolvesAndStoresBothTheReferenceAndTheLegacyNameColumns() {
        when(userRepository.findByEmail("actor@test.com")).thenReturn(Optional.of(User.builder().id(ACTOR_ID).build()));
        SkillMaster skill = SkillMaster.builder().id(UUID.randomUUID()).name("Java").active(true).build();
        ProficiencyLevel level = ProficiencyLevel.builder().id(UUID.randomUUID()).name("Expert").active(true).build();
        when(skillMasterRepo.findById(skill.getId())).thenReturn(Optional.of(skill));
        when(proficiencyLevelRepo.findById(level.getId())).thenReturn(Optional.of(level));

        EmployeeSkillResponse res = service.create("actor@test.com", req(skill.getId(), level.getId()));

        assertEquals(skill.getId(), res.getSkillId());
        assertEquals("Java", res.getSkillName());
        assertEquals(level.getId(), res.getProficiencyLevelId());
        assertEquals("Expert", res.getProficiencyLevel());
    }

    @Test
    void create_rejectsAnInactiveSkill() {
        when(userRepository.findByEmail("actor@test.com")).thenReturn(Optional.of(User.builder().id(ACTOR_ID).build()));
        SkillMaster inactive = SkillMaster.builder().id(UUID.randomUUID()).name("COBOL").active(false).build();
        when(skillMasterRepo.findById(inactive.getId())).thenReturn(Optional.of(inactive));

        assertThrows(IllegalArgumentException.class, () -> service.create("actor@test.com", req(inactive.getId(), UUID.randomUUID())));
    }

    @Test
    void create_rejectsAnInactiveProficiencyLevel() {
        when(userRepository.findByEmail("actor@test.com")).thenReturn(Optional.of(User.builder().id(ACTOR_ID).build()));
        SkillMaster skill = SkillMaster.builder().id(UUID.randomUUID()).name("Java").active(true).build();
        ProficiencyLevel inactiveLevel = ProficiencyLevel.builder().id(UUID.randomUUID()).name("Guru").active(false).build();
        when(skillMasterRepo.findById(skill.getId())).thenReturn(Optional.of(skill));
        when(proficiencyLevelRepo.findById(inactiveLevel.getId())).thenReturn(Optional.of(inactiveLevel));

        assertThrows(IllegalArgumentException.class, () -> service.create("actor@test.com", req(skill.getId(), inactiveLevel.getId())));
    }

    @Test
    void update_allowsKeepingASkillThatHasSinceBeenDeactivated() {
        UUID id = UUID.randomUUID();
        EmployeeSkill existing = EmployeeSkill.builder().id(id).employeeUserId(ACTOR_ID).skillName("COBOL").proficiencyLevel("Beginner").build();
        when(userRepository.findByEmail("actor@test.com")).thenReturn(Optional.of(User.builder().id(ACTOR_ID).build()));
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        SkillMaster nowInactive = SkillMaster.builder().id(UUID.randomUUID()).name("COBOL").active(false).build();
        ProficiencyLevel level = ProficiencyLevel.builder().id(UUID.randomUUID()).name("Advanced").active(true).build();
        when(skillMasterRepo.findById(nowInactive.getId())).thenReturn(Optional.of(nowInactive));
        when(proficiencyLevelRepo.findById(level.getId())).thenReturn(Optional.of(level));

        // Editing proficiency only, keeping the same (now-deactivated) skill, must not be blocked —
        // only *picking* a deactivated skill for the first time (create) is.
        EmployeeSkillResponse res = service.update("actor@test.com", id, req(nowInactive.getId(), level.getId()));

        assertEquals("Advanced", res.getProficiencyLevel());
    }

    @Test
    void update_rejectsEditingSomeoneElsesSkill() {
        UUID id = UUID.randomUUID();
        UUID otherEmployeeId = UUID.randomUUID();
        EmployeeSkill existing = EmployeeSkill.builder().id(id).employeeUserId(otherEmployeeId).build();
        when(userRepository.findByEmail("actor@test.com")).thenReturn(Optional.of(User.builder().id(ACTOR_ID).build()));
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.update("actor@test.com", id, req(UUID.randomUUID(), UUID.randomUUID())));
    }

    @Test
    void listForEmployee_resolvesMigratedRowsButFallsBackToLegacyTextForUnmigratedOnes() {
        UUID javaId = UUID.randomUUID();
        EmployeeSkill migrated = EmployeeSkill.builder().id(UUID.randomUUID()).employeeUserId(ACTOR_ID)
                .skillId(javaId).skillName("Core Java").proficiencyLevel("Expert").build();
        EmployeeSkill unmigrated = EmployeeSkill.builder().id(UUID.randomUUID()).employeeUserId(ACTOR_ID)
                .skillName("COBOL").proficiencyLevel("Advanced").build();
        when(repository.findByEmployeeUserIdOrderByCreatedAtAsc(ACTOR_ID)).thenReturn(List.of(migrated, unmigrated));
        when(skillMasterRepo.findAll()).thenReturn(List.of(SkillMaster.builder().id(javaId).name("Java").build()));
        when(proficiencyLevelRepo.findAll()).thenReturn(List.of());

        List<EmployeeSkillResponse> result = service.listForEmployee(ACTOR_ID);

        assertEquals("Java", result.get(0).getSkillName(), "resolved to the master's current name, not the stored 'Core Java' text");
        assertEquals("COBOL", result.get(1).getSkillName(), "no skill_id yet, so the original free-text value is kept as-is");
    }
}
