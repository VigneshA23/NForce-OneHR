package com.nforce.onehr.service.assignment;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.assignment.ReorderAssignmentRulesRequest;
import com.nforce.onehr.dto.assignment.UpsertAssignmentRuleRequest;
import com.nforce.onehr.entity.AssignmentRule;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AssignmentRuleAssignmentRepository;
import com.nforce.onehr.repository.AssignmentRuleRepository;
import com.nforce.onehr.repository.DepartmentRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LocationRepository;
import com.nforce.onehr.repository.ShiftRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PN-04: HR's rule management - validation, permission and re-evaluation on change. */
@ExtendWith(MockitoExtension.class)
class AssignmentRuleServiceTest {

    @Mock private AssignmentRuleRepository ruleRepository;
    @Mock private AssignmentRuleAssignmentRepository assignmentRepository;
    @Mock private AssignmentRuleEngine engine;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private LocationRepository locationRepository;
    @Mock private ShiftRepository shiftRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EffectivePermissionService effectivePermissionService;
    @Mock private AuditService auditService;
    @Mock private AttendanceProperties attendanceProperties;
    @Mock private AssignmentTargetResolver target;
    @InjectMocks private AssignmentRuleService service;

    private final UUID policy = UUID.randomUUID();
    private final UUID dept = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Employee actor = Employee.builder().userId(UUID.randomUUID()).user(mock(User.class)).build();
        lenient().when(employeeRepository.findByUser_Email("hr@x.com")).thenReturn(Optional.of(actor));
        lenient().when(engine.targetResolver(AssignmentDomain.PENALIZATION)).thenReturn(Optional.of(target));
        lenient().when(target.permissionModule()).thenReturn("penalization");
        lenient().when(target.isUsable(policy)).thenReturn(true);
        lenient().when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        lenient().when(effectivePermissionService.hasAuthority(any(), anyString())).thenReturn(true);
        lenient().when(ruleRepository.save(any())).thenAnswer(i -> { AssignmentRule r = i.getArgument(0); if (r.getId() == null) r.setId(UUID.randomUUID()); return r; });
        lenient().when(ruleRepository.findByDomainOrderByPriorityAscCreatedAtAsc(any())).thenReturn(List.of());
        lenient().when(departmentRepository.existsById(dept)).thenReturn(true);
    }

    private UpsertAssignmentRuleRequest req() {
        UpsertAssignmentRuleRequest r = new UpsertAssignmentRuleRequest();
        r.setDomain(AssignmentDomain.PENALIZATION);
        r.setTargetId(policy);
        r.setName("Engineering");
        r.setDepartmentId(dept);
        return r;
    }

    @Test
    void createSavesTheRuleAndReevaluatesTheWholeDomain() {
        service.create("hr@x.com", req());
        verify(ruleRepository).save(any(AssignmentRule.class));
        verify(engine).reevaluateAll(org.mockito.ArgumentMatchers.eq(AssignmentDomain.PENALIZATION), any());
    }

    @Test
    void aNewRuleGoesAfterEveryExistingOneByDefault() {
        AssignmentRule existing = AssignmentRule.builder().id(UUID.randomUUID()).priority(30).build();
        when(ruleRepository.findByDomainOrderByPriorityAscCreatedAtAsc(AssignmentDomain.PENALIZATION)).thenReturn(List.of(existing));
        service.create("hr@x.com", req());
        org.mockito.ArgumentCaptor<AssignmentRule> c = org.mockito.ArgumentCaptor.forClass(AssignmentRule.class);
        verify(ruleRepository).save(c.capture());
        assertEquals(40, c.getValue().getPriority());
    }

    @Test
    void aRuleWithNoConditionIsRejected() {
        UpsertAssignmentRuleRequest r = req();
        r.setDepartmentId(null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.create("hr@x.com", r));
        assertTrue(e.getMessage().contains("at least one condition"));
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void aRuleCannotPointAtAnInactiveOrMissingPolicy() {
        when(target.isUsable(policy)).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> service.create("hr@x.com", req()));
    }

    @Test
    void aDomainWithoutAResolverIsNotAvailableYet() {
        UpsertAssignmentRuleRequest r = req();
        r.setDomain(AssignmentDomain.LEAVE_PLAN);
        when(engine.targetResolver(AssignmentDomain.LEAVE_PLAN)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.create("hr@x.com", r));
    }

    @Test
    void theDomainsOwnPermissionIsRequired() {
        when(effectivePermissionService.hasAuthority(any(), org.mockito.ArgumentMatchers.eq("penalization:CREATE:ALL"))).thenReturn(false);
        assertThrows(AccessDeniedException.class, () -> service.create("hr@x.com", req()));
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void reorderMustListEveryRuleExactlyOnceThenRenumbers() {
        AssignmentRule a = AssignmentRule.builder().id(UUID.randomUUID()).priority(10).build();
        AssignmentRule b = AssignmentRule.builder().id(UUID.randomUUID()).priority(20).build();
        when(ruleRepository.findByDomainOrderByPriorityAscCreatedAtAsc(AssignmentDomain.PENALIZATION)).thenReturn(List.of(a, b));
        ReorderAssignmentRulesRequest bad = new ReorderAssignmentRulesRequest();
        bad.setDomain(AssignmentDomain.PENALIZATION);
        bad.setOrderedRuleIds(List.of(a.getId()));
        assertThrows(IllegalArgumentException.class, () -> service.reorder("hr@x.com", bad));

        ReorderAssignmentRulesRequest ok = new ReorderAssignmentRulesRequest();
        ok.setDomain(AssignmentDomain.PENALIZATION);
        ok.setOrderedRuleIds(List.of(b.getId(), a.getId()));
        service.reorder("hr@x.com", ok);
        assertEquals(10, b.getPriority());
        assertEquals(20, a.getPriority());
    }
}
