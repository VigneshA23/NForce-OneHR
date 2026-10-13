package com.nforce.onehr.service.talent;

import com.nforce.onehr.dto.talent.ReorderProficiencyLevelsRequest;
import com.nforce.onehr.entity.ProficiencyLevel;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeSkillRepository;
import com.nforce.onehr.repository.ProficiencyLevelRepository;
import com.nforce.onehr.repository.UserRepository;
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
class ProficiencyLevelServiceTest {

    @Mock private ProficiencyLevelRepository proficiencyLevelRepo;
    @Mock private EmployeeSkillRepository employeeSkillRepo;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @InjectMocks private ProficiencyLevelService service;

    private static ProficiencyLevel level(String name, int order) {
        return ProficiencyLevel.builder().id(UUID.randomUUID()).name(name).sortOrder(order).active(true).build();
    }

    @Test
    void deactivate_blockedWhenEmployeeSkillsStillReferenceIt() {
        ProficiencyLevel beginner = level("Beginner", 1);
        when(proficiencyLevelRepo.findById(beginner.getId())).thenReturn(Optional.of(beginner));
        when(employeeSkillRepo.countByProficiencyLevelId(beginner.getId())).thenReturn(3L);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.deactivate(beginner.getId(), "actor@test.com"));
        assertTrue(ex.getMessage().contains("3 employee skill record"));
        verify(proficiencyLevelRepo, never()).save(any());
    }

    @Test
    void deactivate_allowedWhenUnused() {
        ProficiencyLevel beginner = level("Beginner", 1);
        when(proficiencyLevelRepo.findById(beginner.getId())).thenReturn(Optional.of(beginner));
        when(employeeSkillRepo.countByProficiencyLevelId(beginner.getId())).thenReturn(0L);
        when(userRepository.findByEmail(any())).thenReturn(Optional.of(User.builder().id(UUID.randomUUID()).build()));

        service.deactivate(beginner.getId(), "actor@test.com");

        assertFalse(beginner.isActive());
        verify(proficiencyLevelRepo).save(beginner);
    }

    @Test
    void reorder_rejectsRequestMissingAnExistingLevel() {
        ProficiencyLevel a = level("Beginner", 1);
        ProficiencyLevel b = level("Advanced", 2);
        when(proficiencyLevelRepo.findAllByOrderBySortOrderAsc()).thenReturn(List.of(a, b));

        ReorderProficiencyLevelsRequest req = new ReorderProficiencyLevelsRequest();
        req.setOrderedIds(List.of(a.getId())); // missing b

        assertThrows(IllegalArgumentException.class, () -> service.reorder(req));
        verify(proficiencyLevelRepo, never()).saveAll(any());
    }

    @Test
    void reorder_assignsSortOrderByListPosition() {
        ProficiencyLevel a = level("Beginner", 1);
        ProficiencyLevel b = level("Advanced", 2);
        when(proficiencyLevelRepo.findAllByOrderBySortOrderAsc())
                .thenReturn(List.of(a, b))  // validation read
                .thenReturn(List.of(b, a)); // listAll() at the end, post-reorder

        ReorderProficiencyLevelsRequest req = new ReorderProficiencyLevelsRequest();
        req.setOrderedIds(List.of(b.getId(), a.getId())); // swap order
        when(employeeSkillRepo.countByProficiencyLevelId(any())).thenReturn(0L);

        service.reorder(req);

        assertEquals(1, b.getSortOrder());
        assertEquals(2, a.getSortOrder());
    }
}
