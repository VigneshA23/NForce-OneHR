package com.nforce.onehr.service;

import com.nforce.onehr.dto.allocation.BulkAllocationResponse;
import com.nforce.onehr.dto.allocation.BulkProjectAllocationRequest;
import com.nforce.onehr.dto.allocation.EmployeeCapacityResponse;
import com.nforce.onehr.dto.allocation.ProjectAllocationRequest;
import com.nforce.onehr.dto.allocation.ProjectAllocationResponse;
import com.nforce.onehr.entity.Department;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Project;
import com.nforce.onehr.entity.ProjectAllocation;
import com.nforce.onehr.entity.ProjectStatus;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.ProjectAllocationRepository;
import com.nforce.onehr.repository.ProjectRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * US-B04 (single allocate) / US-B06 (capacity validation) / US-B10 (bulk allocate): verifies both
 * entry points share the same validation (no inactive project, no inverted dates, no over-100%
 * capacity) and that a bulk request isolates one employee's failure from the rest.
 *
 * <p>US-B05/US-B13 (capacityView): verifies the department/project filters combine with AND, and
 * that a Manager's visibility (sourced from {@link DirectReportScopeService}, the same hierarchy
 * query behind Leave approvals) is never widened by either filter — only narrowed.
 */
@ExtendWith(MockitoExtension.class)
class ProjectAllocationServiceTest {

    @Mock private ProjectAllocationRepository allocationRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private UserRepository userRepository;
    @Mock private DirectReportScopeService directReportScopeService;

    private ProjectAllocationService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private final LocalDate start = LocalDate.of(2026, 1, 1);
    private final LocalDate end = LocalDate.of(2026, 1, 31);

    private static final String HR_EMAIL = "hr@test.com";
    private static final String OWNER_EMAIL = "owner@test.com";
    private final UUID managerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProjectAllocationService(allocationRepository, employeeRepository, projectRepository,
                userRepository, directReportScopeService);
        lenient().when(userRepository.findByEmail(HR_EMAIL)).thenReturn(Optional.of(User.builder()
                .id(UUID.randomUUID()).email(HR_EMAIL).roles(Set.of(Role.builder().code("HR_ADMIN").build())).build()));
        lenient().when(userRepository.findByEmail(OWNER_EMAIL)).thenReturn(Optional.of(User.builder()
                .id(managerId).email(OWNER_EMAIL).roles(Set.of(Role.builder().code("MANAGER").build())).build()));
        lenient().when(allocationRepository.save(any())).thenAnswer(inv -> {
            ProjectAllocation a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            return a;
        });
    }

    private Employee employee(UUID id) {
        return Employee.builder().userId(id).fullName("Test Employee").build();
    }

    private Employee employee(UUID id, String name, Department department) {
        return Employee.builder().userId(id).fullName(name).department(department).build();
    }

    private User userWithRole(String roleCode) {
        return User.builder().id(UUID.randomUUID()).email("actor@test.com")
                .roles(Set.of(Role.builder().code(roleCode).build())).build();
    }

    private Project activeProject() {
        return Project.builder().id(projectId).name("Phoenix").status(ProjectStatus.ACTIVE).build();
    }

    private ProjectAllocationRequest request(UUID empId, int capacity, LocalDate from, LocalDate to) {
        ProjectAllocationRequest req = new ProjectAllocationRequest();
        req.setEmployeeId(empId);
        req.setProjectId(projectId);
        req.setCapacityPercent(capacity);
        req.setStartDate(from);
        req.setEndDate(to);
        return req;
    }

    @Test
    void allocate_savesWhenWithinCapacity() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee(employeeId)));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(activeProject()));
        when(allocationRepository.sumOverlappingCapacity(employeeId, start, end)).thenReturn(0L);

        ProjectAllocationResponse res = service.allocate(HR_EMAIL, request(employeeId, 60, start, end));

        assertEquals(employeeId, res.getEmployeeId());
        assertEquals(projectId, res.getProjectId());
        assertEquals(60, res.getCapacityPercent());
        verify(allocationRepository).save(any());
    }

    @Test
    void allocate_throwsWhenEmployeeMissing() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(activeProject()));
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.allocate(HR_EMAIL, request(employeeId, 50, start, end)));
        verify(allocationRepository, never()).save(any());
    }

    @Test
    void allocate_throwsWhenProjectInactive() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee(employeeId)));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(
                Project.builder().id(projectId).name("Legacy").status(ProjectStatus.CLOSED).build()));

        assertThrows(IllegalArgumentException.class, () -> service.allocate(HR_EMAIL, request(employeeId, 50, start, end)));
        verify(allocationRepository, never()).save(any());
    }

    @Test
    void allocate_throwsWhenEndBeforeStart() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee(employeeId)));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(activeProject()));

        assertThrows(IllegalArgumentException.class,
                () -> service.allocate(HR_EMAIL, request(employeeId, 50, end, start)));
        verify(allocationRepository, never()).save(any());
    }

    @Test
    void allocate_over100Percent_warnsAndDoesNotSave() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee(employeeId)));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(activeProject()));
        when(allocationRepository.sumOverlappingCapacity(employeeId, start, end)).thenReturn(70L);

        ProjectAllocationResponse res = service.allocate(HR_EMAIL, request(employeeId, 40, start, end));

        assertNull(res.getId());
        assertEquals(110, res.getProjectedCapacityPercent());
        assertNotNull(res.getWarning());
        verify(allocationRepository, never()).save(any());
    }

    @Test
    void allocate_over100Percent_confirmed_savesWithWarning() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee(employeeId)));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(activeProject()));
        when(allocationRepository.sumOverlappingCapacity(employeeId, start, end)).thenReturn(70L);
        ProjectAllocationRequest req = request(employeeId, 40, start, end);
        req.setConfirm(true);

        ProjectAllocationResponse res = service.allocate(HR_EMAIL, req);

        assertNotNull(res.getId());
        assertNotNull(res.getWarning());
        verify(allocationRepository).save(any());
    }

    @Test
    void allocate_exactly100Percent_savesWithoutWarning() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee(employeeId)));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(activeProject()));
        when(allocationRepository.sumOverlappingCapacity(employeeId, start, end)).thenReturn(60L);

        ProjectAllocationResponse res = service.allocate(HR_EMAIL, request(employeeId, 40, start, end));

        assertNotNull(res.getId());
        assertNull(res.getWarning());
    }

    @Test
    void bulkAllocate_isolatesOneFailureFromTheRest() {
        UUID okEmployee = UUID.randomUUID();
        UUID overAllocatedEmployee = UUID.randomUUID();
        UUID missingEmployee = UUID.randomUUID();

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(activeProject()));
        when(employeeRepository.findById(okEmployee)).thenReturn(Optional.of(employee(okEmployee)));
        when(employeeRepository.findById(overAllocatedEmployee)).thenReturn(Optional.of(employee(overAllocatedEmployee)));
        when(employeeRepository.findById(missingEmployee)).thenReturn(Optional.empty());

        when(allocationRepository.sumOverlappingCapacity(okEmployee, start, end)).thenReturn(0L);
        when(allocationRepository.sumOverlappingCapacity(overAllocatedEmployee, start, end)).thenReturn(80L);

        BulkProjectAllocationRequest req = new BulkProjectAllocationRequest();
        req.setEmployeeIds(List.of(okEmployee, overAllocatedEmployee, missingEmployee));
        req.setProjectId(projectId);
        req.setCapacityPercent(50);
        req.setStartDate(start);
        req.setEndDate(end);

        BulkAllocationResponse res = service.bulkAllocate(HR_EMAIL, req);

        assertEquals(3, res.getResults().size());
        assertEquals("SUCCESS", statusFor(res, okEmployee));
        assertEquals("WARNING", statusFor(res, overAllocatedEmployee));
        assertEquals("FAILED", statusFor(res, missingEmployee));
        verify(allocationRepository, times(1)).save(any());

        // Resubmitting just the warned employee with confirm=true persists it.
        req.setEmployeeIds(List.of(overAllocatedEmployee));
        req.setConfirm(true);
        assertEquals("SUCCESS", statusFor(service.bulkAllocate(HR_EMAIL, req), overAllocatedEmployee));
        verify(allocationRepository, times(2)).save(any());
    }

    private String statusFor(BulkAllocationResponse res, UUID employeeId) {
        return res.getResults().stream()
                .filter(r -> r.getEmployeeId().equals(employeeId))
                .findFirst().orElseThrow()
                .getStatus();
    }

    // ── capacityView() — US-B05 baseline + US-B13 department/project filters ──────────────────

    private static final String MANAGER_EMAIL = "manager@test.com";

    private List<UUID> idsOf(List<EmployeeCapacityResponse> rows) {
        return rows.stream().map(EmployeeCapacityResponse::getEmployeeId).collect(Collectors.toList());
    }

    // ── US-B03: only the project owner or HR may allocate ───────────────────────

    private Project projectOwnedBy(UUID ownerId) {
        return Project.builder().id(projectId).name("Phoenix").status(ProjectStatus.ACTIVE).ownerEmployeeId(ownerId).build();
    }

    @Test
    void allocate_managerWhoOwnsProject_isAllowed() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectOwnedBy(managerId)));
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee(employeeId)));
        when(allocationRepository.sumOverlappingCapacity(employeeId, start, end)).thenReturn(0L);

        ProjectAllocationResponse res = service.allocate(OWNER_EMAIL, request(employeeId, 50, start, end));

        assertEquals(projectId, res.getProjectId());
        verify(allocationRepository).save(any());
    }

    @Test
    void allocate_managerWhoDoesNotOwnProject_isDeniedAndNothingSaved() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectOwnedBy(UUID.randomUUID())));

        assertThrows(AccessDeniedException.class,
                () -> service.allocate(OWNER_EMAIL, request(employeeId, 50, start, end)));
        verify(allocationRepository, never()).save(any());
    }

    @Test
    void allocate_managerOnUnownedProject_isDenied() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectOwnedBy(null)));

        assertThrows(AccessDeniedException.class,
                () -> service.allocate(OWNER_EMAIL, request(employeeId, 50, start, end)));
    }

    @Test
    void allocate_hrAdmin_mayAllocateOnAnyonesProject() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectOwnedBy(UUID.randomUUID())));
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee(employeeId)));
        when(allocationRepository.sumOverlappingCapacity(employeeId, start, end)).thenReturn(0L);

        service.allocate(HR_EMAIL, request(employeeId, 50, start, end));

        verify(allocationRepository).save(any());
    }

    @Test
    void bulkAllocate_nonOwner_rejectsWholeRequestInsteadOfPerEmployeeFailures() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectOwnedBy(UUID.randomUUID())));
        BulkProjectAllocationRequest req = new BulkProjectAllocationRequest();
        req.setEmployeeIds(List.of(employeeId));
        req.setProjectId(projectId);
        req.setCapacityPercent(50);
        req.setStartDate(start);
        req.setEndDate(end);

        assertThrows(AccessDeniedException.class, () -> service.bulkAllocate(OWNER_EMAIL, req));
        verify(allocationRepository, never()).save(any());
    }

    @Test
    void allocate_onHoldProject_isRejected() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(
                Project.builder().id(projectId).name("Paused").status(ProjectStatus.ON_HOLD).build()));
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee(employeeId)));

        assertThrows(IllegalArgumentException.class, () -> service.allocate(HR_EMAIL, request(employeeId, 50, start, end)));
        verify(allocationRepository, never()).save(any());
    }

    @Test
    void capacityView_noFilters_managerSeesExactlyTheirDirectReports() {
        Department deptX = Department.builder().id(UUID.randomUUID()).name("Engineering").build();
        UUID empA = UUID.randomUUID();
        UUID empB = UUID.randomUUID();
        when(userRepository.findByEmail(MANAGER_EMAIL)).thenReturn(Optional.of(userWithRole("MANAGER")));
        when(directReportScopeService.directReportIds(MANAGER_EMAIL)).thenReturn(Set.of(empA, empB));
        when(employeeRepository.findAllById(Set.of(empA, empB))).thenReturn(
                List.of(employee(empA, "Alice", deptX), employee(empB, "Bob", deptX)));
        when(allocationRepository.findByEmployeeUserIdIn(any())).thenReturn(List.of());

        List<EmployeeCapacityResponse> res = service.capacityView(MANAGER_EMAIL, null, null);

        assertEquals(2, res.size());
        assertTrue(idsOf(res).containsAll(List.of(empA, empB)));
        verify(employeeRepository, never()).findAll();
    }

    @Test
    void capacityView_departmentFilter_narrowsWithinManagerHierarchy_excludesOutsideEmployeeInSameDepartment() {
        Department deptX = Department.builder().id(UUID.randomUUID()).name("Engineering").build();
        Department deptZ = Department.builder().id(UUID.randomUUID()).name("Sales").build();
        UUID empA = UUID.randomUUID(); // manager's report, deptX
        UUID empD = UUID.randomUUID(); // manager's report, deptZ
        UUID empOutside = UUID.randomUUID(); // NOT a report — same deptX as empA

        when(userRepository.findByEmail(MANAGER_EMAIL)).thenReturn(Optional.of(userWithRole("MANAGER")));
        when(directReportScopeService.directReportIds(MANAGER_EMAIL)).thenReturn(Set.of(empA, empD));
        // Manager's scope is resolved via findAllById — empOutside is never even looked up,
        // so there is no path by which the department filter could surface them.
        when(employeeRepository.findAllById(Set.of(empA, empD))).thenReturn(
                List.of(employee(empA, "Alice", deptX), employee(empD, "Dana", deptZ)));
        when(allocationRepository.findByEmployeeUserIdIn(any())).thenReturn(List.of());

        List<EmployeeCapacityResponse> res = service.capacityView(MANAGER_EMAIL, deptX.getId(), null);

        assertEquals(1, res.size());
        assertEquals(empA, res.get(0).getEmployeeId());
        assertFalse(idsOf(res).contains(empOutside));
    }

    @Test
    void capacityView_projectFilter_narrowsWithinManagerHierarchy_cannotWidenToOutsideEmployeeOnSameProject() {
        UUID empA = UUID.randomUUID(); // manager's report, allocated to the project
        UUID empD = UUID.randomUUID(); // manager's report, NOT allocated to the project
        UUID empOutside = UUID.randomUUID(); // NOT a report — also allocated to the project

        when(userRepository.findByEmail(MANAGER_EMAIL)).thenReturn(Optional.of(userWithRole("MANAGER")));
        when(directReportScopeService.directReportIds(MANAGER_EMAIL)).thenReturn(Set.of(empA, empD));
        when(allocationRepository.findDistinctEmployeeUserIdsByProjectId(projectId))
                .thenReturn(List.of(empA, empOutside));
        when(employeeRepository.findAllById(Set.of(empA))).thenReturn(List.of(employee(empA)));
        when(allocationRepository.findByEmployeeUserIdIn(any())).thenReturn(List.of());

        List<EmployeeCapacityResponse> res = service.capacityView(MANAGER_EMAIL, null, projectId);

        assertEquals(1, res.size());
        assertEquals(empA, res.get(0).getEmployeeId());
        verify(employeeRepository, never()).findAllById(argThat(ids -> containsId(ids, empOutside)));
    }

    private static boolean containsId(Iterable<UUID> ids, UUID target) {
        for (UUID id : ids) {
            if (target.equals(id)) return true;
        }
        return false;
    }

    @Test
    void capacityView_departmentAndProject_combineWithAnd() {
        Department deptX = Department.builder().id(UUID.randomUUID()).name("Engineering").build();
        UUID empA = UUID.randomUUID(); // deptX, allocated to projectId — matches both filters
        UUID empD = UUID.randomUUID(); // deptX, NOT allocated to projectId — fails the AND

        when(userRepository.findByEmail(MANAGER_EMAIL)).thenReturn(Optional.of(userWithRole("MANAGER")));
        when(directReportScopeService.directReportIds(MANAGER_EMAIL)).thenReturn(Set.of(empA, empD));
        when(allocationRepository.findDistinctEmployeeUserIdsByProjectId(projectId)).thenReturn(List.of(empA));
        when(employeeRepository.findAllById(Set.of(empA))).thenReturn(
                List.of(employee(empA, "Alice", deptX)));
        when(allocationRepository.findByEmployeeUserIdIn(any())).thenReturn(List.of());

        List<EmployeeCapacityResponse> res = service.capacityView(MANAGER_EMAIL, deptX.getId(), projectId);

        assertEquals(1, res.size());
        assertEquals(empA, res.get(0).getEmployeeId());
    }

    @Test
    void capacityView_managerWithNoDirectReports_returnsEmptyRegardlessOfFilters() {
        when(userRepository.findByEmail(MANAGER_EMAIL)).thenReturn(Optional.of(userWithRole("MANAGER")));
        when(directReportScopeService.directReportIds(MANAGER_EMAIL)).thenReturn(Set.of());

        List<EmployeeCapacityResponse> res = service.capacityView(MANAGER_EMAIL, UUID.randomUUID(), projectId);

        assertTrue(res.isEmpty());
        verifyNoInteractions(employeeRepository);
    }

    @Test
    void capacityView_hrAdminOverride_seesOrgWideUnrestrictedByHierarchy() {
        String hrEmail = "hr@test.com";
        UUID anyEmployee = UUID.randomUUID();
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(userWithRole("HR_ADMIN")));
        when(employeeRepository.findAll()).thenReturn(List.of(employee(anyEmployee)));
        when(allocationRepository.findByEmployeeUserIdIn(any())).thenReturn(List.of());

        List<EmployeeCapacityResponse> res = service.capacityView(hrEmail, null, null);

        assertEquals(1, res.size());
        verifyNoInteractions(directReportScopeService);
    }
}
