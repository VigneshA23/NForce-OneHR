package com.nforce.onehr.service;

import com.nforce.onehr.dto.allocation.ProjectAllocationRequest;
import com.nforce.onehr.dto.allocation.ProjectAllocationResponse;
import com.nforce.onehr.dto.opportunity.OpportunityDtos.CloseOpportunityRequest;
import com.nforce.onehr.dto.opportunity.OpportunityDtos.OpportunityRequest;
import com.nforce.onehr.dto.opportunity.OpportunityDtos.OpportunityResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.InternalOpportunity;
import com.nforce.onehr.entity.OpportunityInterest;
import com.nforce.onehr.entity.OpportunityStatus;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.SkillTaxonomy;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.BenchStatusRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.InternalOpportunityRepository;
import com.nforce.onehr.repository.OpportunityInterestRepository;
import com.nforce.onehr.repository.SkillTaxonomyRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * US-A10 (who can post / who can see), US-A11 (one interest each, poster notified) and US-A12
 * (allocation + bench end + close in one go, failing as a unit).
 */
@ExtendWith(MockitoExtension.class)
class OpportunityServiceTest {

    @Mock private InternalOpportunityRepository opportunityRepository;
    @Mock private OpportunityInterestRepository interestRepository;
    @Mock private SkillTaxonomyRepository taxonomyRepository;
    @Mock private BenchStatusRepository benchRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;
    @Mock private ProjectAllocationService allocationService;
    @Mock private BenchStatusService benchStatusService;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer snapshotSerializer;

    private OpportunityService service;

    private final UUID skillId = UUID.randomUUID();
    private final User hr = user("HR_ADMIN");
    private final User manager = user("MANAGER");
    private final User benchedEmployee = user("EMPLOYEE");
    private final User activeEmployee = user("EMPLOYEE");

    private static User user(String role) {
        User u = new User();
        u.setId(UUID.randomUUID());
        Role r = new Role();
        r.setCode(role);
        u.setRoles(Set.of(r));
        return u;
    }

    @BeforeEach
    void setUp() {
        service = new OpportunityService(opportunityRepository, interestRepository, taxonomyRepository, benchRepository,
                employeeRepository, userRepository, notificationService, allocationService, benchStatusService, auditService, snapshotSerializer);
        lenient().when(userRepository.findByEmail("hr@t")).thenReturn(Optional.of(hr));
        lenient().when(userRepository.findByEmail("mgr@t")).thenReturn(Optional.of(manager));
        lenient().when(userRepository.findByEmail("benched@t")).thenReturn(Optional.of(benchedEmployee));
        lenient().when(userRepository.findByEmail("active@t")).thenReturn(Optional.of(activeEmployee));
        lenient().when(benchRepository.existsByEmployeeUserIdAndActiveTrue(benchedEmployee.getId())).thenReturn(true);
        lenient().when(taxonomyRepository.findAll()).thenReturn(List.of(
                SkillTaxonomy.builder().id(skillId).skill("Java").category("Eng").build()));
        lenient().when(employeeRepository.findAllById(any())).thenReturn(List.of());
        lenient().when(opportunityRepository.save(any())).thenAnswer(inv -> {
            InternalOpportunity o = inv.getArgument(0);
            if (o.getId() == null) o.setId(UUID.randomUUID());
            return o;
        });
    }

    private InternalOpportunity openOpportunity() {
        InternalOpportunity o = InternalOpportunity.builder().id(UUID.randomUUID()).title("Backend help")
                .durationWeeks(6).postedByUserId(manager.getId()).requiredSkillIds(Set.of(skillId)).build();
        lenient().when(opportunityRepository.findById(o.getId())).thenReturn(Optional.of(o));
        return o;
    }

    // ---- US-A10 ----

    @Test
    void post_isAllowedForHrAndManager_withTaxonomySkills() {
        OpportunityResponse res = service.post("mgr@t", new OpportunityRequest("Backend help", 6, Set.of(skillId)));

        assertEquals(OpportunityStatus.OPEN, res.status());
        assertEquals(List.of("Java"), res.requiredSkills());
    }

    @Test
    void post_isRefusedForARegularEmployee() {
        assertThrows(AccessDeniedException.class,
                () -> service.post("active@t", new OpportunityRequest("x", 1, Set.of(skillId))));
        verify(opportunityRepository, never()).save(any());
    }

    @Test
    void post_rejectsASkillThatIsNotInTheTaxonomy() {
        assertThrows(NoSuchElementException.class,
                () -> service.post("hr@t", new OpportunityRequest("x", 1, Set.of(UUID.randomUUID()))));
    }

    @Test
    void list_showsOnlyOpenOnesToABenchedEmployee() {
        InternalOpportunity opp = openOpportunity();
        when(opportunityRepository.findByStatusOrderByCreatedAtDesc(OpportunityStatus.OPEN)).thenReturn(List.of(opp));

        assertEquals(1, service.list("benched@t").size());
        verify(opportunityRepository, never()).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void list_isEmptyForAnEmployeeWhoIsNotOnBench() {
        assertTrue(service.list("active@t").isEmpty());
        verifyNoInteractions(opportunityRepository);
    }

    @Test
    void list_showsEveryOpportunityToHr() {
        InternalOpportunity opp = openOpportunity();
        when(opportunityRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(opp));

        assertEquals(1, service.list("hr@t").size());
    }

    // ---- US-A11 ----

    @Test
    void expressInterest_savesItAndNotifiesThePoster() {
        InternalOpportunity opp = openOpportunity();
        when(employeeRepository.findById(benchedEmployee.getId()))
                .thenReturn(Optional.of(Employee.builder().userId(benchedEmployee.getId()).fullName("Asha").build()));

        service.expressInterest("benched@t", opp.getId());

        verify(interestRepository).save(any(OpportunityInterest.class));
        verify(notificationService).send(eq(manager.getId()), eq("OPPORTUNITY_INTEREST"), any(), any(), any());
    }

    @Test
    void expressInterest_rejectsAnEmployeeWhoIsNotOnBench() {
        InternalOpportunity opp = openOpportunity();

        assertThrows(IllegalStateException.class, () -> service.expressInterest("active@t", opp.getId()));
        verifyNoInteractions(notificationService);
    }

    @Test
    void expressInterest_rejectsADuplicate() {
        InternalOpportunity opp = openOpportunity();
        when(interestRepository.existsByOpportunityIdAndEmployeeUserId(opp.getId(), benchedEmployee.getId())).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.expressInterest("benched@t", opp.getId()));
        verify(interestRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void expressInterest_rejectsAClosedOpportunity() {
        InternalOpportunity opp = openOpportunity();
        opp.setStatus(OpportunityStatus.CLOSED);

        assertThrows(IllegalStateException.class, () -> service.expressInterest("benched@t", opp.getId()));
    }

    @Test
    void interests_areHiddenFromAnEmployeeWhoDidNotPostIt() {
        InternalOpportunity opp = openOpportunity();

        assertThrows(AccessDeniedException.class, () -> service.interests("benched@t", opp.getId()));
    }

    // ---- US-A12 ----

    private CloseOpportunityRequest closeRequest() {
        return new CloseOpportunityRequest(benchedEmployee.getId(), UUID.randomUUID(), 100,
                LocalDate.now(), LocalDate.now().plusWeeks(6));
    }

    @Test
    void close_allocates_endsBench_thenClosesTheOpportunity() {
        InternalOpportunity opp = openOpportunity();
        UUID allocationId = UUID.randomUUID();
        when(allocationService.allocate(any(ProjectAllocationRequest.class)))
                .thenReturn(ProjectAllocationResponse.builder().id(allocationId).build());

        OpportunityResponse res = service.close("hr@t", opp.getId(), closeRequest());

        InOrder order = inOrder(allocationService, benchStatusService, opportunityRepository);
        order.verify(allocationService).allocate(any(ProjectAllocationRequest.class));
        order.verify(benchStatusService).endForAllocation(eq(hr.getId()), eq(benchedEmployee.getId()), eq(allocationId), any());
        order.verify(opportunityRepository).save(opp);
        assertEquals(OpportunityStatus.CLOSED, res.status());
        assertEquals(benchedEmployee.getId(), opp.getAssignedEmployeeUserId());
        verify(auditService).log(eq(hr.getId()), eq("INTERNAL_OPPORTUNITY_CLOSED"), eq(benchedEmployee.getId()), isNull(), any());
    }

    @Test
    void close_worksWhenThePosterIsAlsoTheAssignee() {
        InternalOpportunity opp = openOpportunity();
        opp.setPostedByUserId(benchedEmployee.getId());
        when(allocationService.allocate(any(ProjectAllocationRequest.class)))
                .thenReturn(ProjectAllocationResponse.builder().id(UUID.randomUUID()).build());

        OpportunityResponse res = service.close("hr@t", opp.getId(), closeRequest());

        assertEquals(OpportunityStatus.CLOSED, res.status());
    }

    @Test
    void close_whenTheAllocationFails_leavesBenchAndOpportunityUntouched() {
        InternalOpportunity opp = openOpportunity();
        when(allocationService.allocate(any(ProjectAllocationRequest.class)))
                .thenThrow(new IllegalArgumentException("Capacity exceeds 100%"));

        assertThrows(IllegalArgumentException.class, () -> service.close("hr@t", opp.getId(), closeRequest()));

        verifyNoInteractions(benchStatusService);
        verify(opportunityRepository, never()).save(any());
        assertEquals(OpportunityStatus.OPEN, opp.getStatus());
        verifyNoInteractions(auditService);
    }

    @Test
    void close_whenEndingBenchFails_neverClosesTheOpportunity() {
        InternalOpportunity opp = openOpportunity();
        when(allocationService.allocate(any(ProjectAllocationRequest.class)))
                .thenReturn(ProjectAllocationResponse.builder().id(UUID.randomUUID()).build());
        when(benchStatusService.endForAllocation(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Employee is not on bench"));

        assertThrows(IllegalStateException.class, () -> service.close("hr@t", opp.getId(), closeRequest()));

        verify(opportunityRepository, never()).save(any());
        assertEquals(OpportunityStatus.OPEN, opp.getStatus());
    }

    @Test
    void close_isHrOnly_andRejectsAnAlreadyClosedOpportunity() {
        InternalOpportunity opp = openOpportunity();
        assertThrows(AccessDeniedException.class, () -> service.close("mgr@t", opp.getId(), closeRequest()));

        opp.setStatus(OpportunityStatus.CLOSED);
        assertThrows(IllegalStateException.class, () -> service.close("hr@t", opp.getId(), closeRequest()));
        verifyNoInteractions(allocationService);
    }
}
