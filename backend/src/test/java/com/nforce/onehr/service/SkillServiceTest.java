package com.nforce.onehr.service;

import com.nforce.onehr.dto.skill.SkillDtos.RatingRequest;
import com.nforce.onehr.dto.skill.SkillDtos.RatingResponse;
import com.nforce.onehr.dto.skill.SkillDtos.SkillRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeSkillRating;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.SkillTaxonomy;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.EmployeeSkillRatingRepository;
import com.nforce.onehr.repository.SkillTaxonomyRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * US-A07: taxonomy-only skills, self-rating as the employee's own write path, and the manager
 * rating restricted to the employee's manager or HR.
 */
@ExtendWith(MockitoExtension.class)
class SkillServiceTest {

    @Mock private SkillTaxonomyRepository taxonomyRepository;
    @Mock private EmployeeSkillRatingRepository ratingRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserRepository userRepository;
    @Mock private DirectReportScopeService directReportScopeService;

    private SkillService service;

    private final UUID me = UUID.randomUUID();
    private final UUID skillId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SkillService(taxonomyRepository, ratingRepository, employeeRepository, userRepository,
                directReportScopeService);
        lenient().when(directReportScopeService.resolveActor("me@test.com"))
                .thenReturn(Employee.builder().userId(me).fullName("Me").build());
        lenient().when(ratingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private User userWithRole(String code) {
        User u = new User();
        u.setId(UUID.randomUUID());
        Role r = new Role();
        r.setCode(code);
        u.setRoles(Set.of(r));
        return u;
    }

    @Test
    void addSkill_rejectsADuplicateIgnoringCase() {
        when(taxonomyRepository.existsBySkillIgnoreCase("java")).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.addSkill(new SkillRequest("java", "Eng")));
        verify(taxonomyRepository, never()).save(any());
    }

    @Test
    void rateSelf_createsAnEntryForATaxonomySkill() {
        when(taxonomyRepository.existsById(skillId)).thenReturn(true);
        when(ratingRepository.findByEmployeeUserIdAndSkillId(me, skillId)).thenReturn(Optional.empty());

        service.rateSelf("me@test.com", new RatingRequest(skillId, 4));

        ArgumentCaptor<EmployeeSkillRating> saved = ArgumentCaptor.forClass(EmployeeSkillRating.class);
        verify(ratingRepository).save(saved.capture());
        assertEquals(4, saved.getValue().getSelfRating());
        assertEquals(me, saved.getValue().getEmployeeUserId());
    }

    @Test
    void rateSelf_updatesAnExistingEntryWithoutTouchingTheManagerRating() {
        EmployeeSkillRating existing = EmployeeSkillRating.builder().employeeUserId(me).skillId(skillId)
                .selfRating(2).managerRating(5).build();
        when(taxonomyRepository.existsById(skillId)).thenReturn(true);
        when(ratingRepository.findByEmployeeUserIdAndSkillId(me, skillId)).thenReturn(Optional.of(existing));

        service.rateSelf("me@test.com", new RatingRequest(skillId, 3));

        assertEquals(3, existing.getSelfRating());
        assertEquals(5, existing.getManagerRating());
    }

    @Test
    void rateSelf_rejectsASkillOutsideTheTaxonomy() {
        when(taxonomyRepository.existsById(skillId)).thenReturn(false);

        assertThrows(NoSuchElementException.class, () -> service.rateSelf("me@test.com", new RatingRequest(skillId, 3)));
    }

    @Test
    void myRatings_joinsTheTaxonomyNames() {
        when(taxonomyRepository.findAll()).thenReturn(List.of(
                SkillTaxonomy.builder().id(skillId).skill("Java").category("Eng").build()));
        when(ratingRepository.findByEmployeeUserId(me)).thenReturn(List.of(
                EmployeeSkillRating.builder().employeeUserId(me).skillId(skillId).selfRating(4).managerRating(3).build()));

        List<RatingResponse> mine = service.myRatings("me@test.com");

        assertEquals("Java", mine.get(0).skill());
        assertEquals(3, mine.get(0).managerRating());
    }

    @Test
    void rateAsManager_allowsTheEmployeesManager() {
        UUID report = UUID.randomUUID();
        EmployeeSkillRating existing = EmployeeSkillRating.builder().employeeUserId(report).skillId(skillId).selfRating(2).build();
        when(userRepository.findByEmail("mgr@test.com")).thenReturn(Optional.of(userWithRole("MANAGER")));
        when(directReportScopeService.isDirectReport("mgr@test.com", report)).thenReturn(true);
        when(ratingRepository.findByEmployeeUserIdAndSkillId(report, skillId)).thenReturn(Optional.of(existing));

        service.rateAsManager("mgr@test.com", report, skillId, 4);

        assertEquals(4, existing.getManagerRating());
        assertEquals(2, existing.getSelfRating());
    }

    @Test
    void rateAsManager_refusesAManagerOfSomeoneElse() {
        UUID stranger = UUID.randomUUID();
        when(userRepository.findByEmail("mgr@test.com")).thenReturn(Optional.of(userWithRole("MANAGER")));
        when(directReportScopeService.isDirectReport("mgr@test.com", stranger)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> service.rateAsManager("mgr@test.com", stranger, skillId, 4));
        verify(ratingRepository, never()).save(any());
    }

    @Test
    void rateAsManager_letsHrOverrideAnyone_withoutAHierarchyCheck() {
        UUID anyone = UUID.randomUUID();
        EmployeeSkillRating existing = EmployeeSkillRating.builder().employeeUserId(anyone).skillId(skillId).selfRating(2).build();
        when(userRepository.findByEmail("hr@test.com")).thenReturn(Optional.of(userWithRole("HR_ADMIN")));
        when(ratingRepository.findByEmployeeUserIdAndSkillId(anyone, skillId)).thenReturn(Optional.of(existing));

        service.rateAsManager("hr@test.com", anyone, skillId, 5);

        assertEquals(5, existing.getManagerRating());
        verifyNoInteractions(directReportScopeService);
    }

    @Test
    void rateAsManager_rejectsARatingOutsideOneToFive_beforeTouchingAnything() {
        assertThrows(IllegalArgumentException.class, () -> service.rateAsManager("hr@test.com", UUID.randomUUID(), skillId, 9));
        assertThrows(IllegalArgumentException.class, () -> service.rateAsManager("hr@test.com", UUID.randomUUID(), skillId, 0));
        verifyNoInteractions(ratingRepository, userRepository);
    }

    @Test
    void rateAsManager_needsTheEmployeeToHaveListedTheSkill() {
        UUID report = UUID.randomUUID();
        when(userRepository.findByEmail("hr@test.com")).thenReturn(Optional.of(userWithRole("HR_ADMIN")));
        when(ratingRepository.findByEmployeeUserIdAndSkillId(report, skillId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.rateAsManager("hr@test.com", report, skillId, 3));
    }
}
