package com.nforce.onehr.service;

import com.nforce.onehr.dto.bench.BenchStatusRequest;
import com.nforce.onehr.dto.bench.BenchStatusResponse;
import com.nforce.onehr.dto.bench.EndBenchRequest;
import com.nforce.onehr.dto.bench.TeamBenchResponse;
import com.nforce.onehr.entity.BenchCloseReason;
import com.nforce.onehr.entity.BenchReasonCode;
import com.nforce.onehr.entity.BenchStatus;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Project;
import com.nforce.onehr.entity.ProjectAllocation;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.BenchStatusRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.ProjectAllocationRepository;
import com.nforce.onehr.repository.ProjectRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * US-A01: marking/ending bench status is validated, defaulted and audited. US-A02: the team read
 * only ever loads bench rows for the caller's own direct reports (never HR-wide), and "last
 * project" comes from the employee's latest-ending allocation.
 */
@ExtendWith(MockitoExtension.class)
class BenchStatusServiceTest {

    private static final String ACTOR_EMAIL = "hr@test.com";

    @Mock private BenchStatusRepository benchRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserRepository userRepository;
    @Mock private ProjectAllocationRepository allocationRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private DirectReportScopeService directReportScopeService;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer snapshotSerializer;
    @Mock private BenchAgingService agingService;

    private BenchStatusService service;

    private final UUID actorId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private Employee employee;

    @BeforeEach
    void setUp() {
        service = new BenchStatusService(benchRepository, employeeRepository, userRepository, allocationRepository,
                projectRepository, directReportScopeService, auditService, snapshotSerializer, agingService);
        User actor = new User();
        actor.setId(actorId);
        employee = Employee.builder().userId(employeeId).fullName("Asha Rao").build();
        lenient().when(userRepository.findByEmail(ACTOR_EMAIL)).thenReturn(Optional.of(actor));
        lenient().when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        lenient().when(snapshotSerializer.toJson(any())).thenReturn("{}");
        lenient().when(benchRepository.save(any())).thenAnswer(inv -> {
            BenchStatus b = inv.getArgument(0);
            if (b.getId() == null) b.setId(UUID.randomUUID());
            return b;
        });
    }

    private BenchStatusRequest request(LocalDate startDate) {
        BenchStatusRequest req = new BenchStatusRequest();
        req.setEmployeeId(employeeId);
        req.setReasonCode(BenchReasonCode.PROJECT_ENDED);
        req.setStartDate(startDate);
        return req;
    }

    // ---- US-A01 ----

    @Test
    void start_defaultsStartDateToToday_andAudits() {
        BenchStatusResponse res = service.start(ACTOR_EMAIL, request(null));

        assertEquals(LocalDate.now(), res.getStartDate());
        assertEquals(BenchReasonCode.PROJECT_ENDED, res.getReasonCode());
        assertTrue(res.isActive());
        verify(auditService).log(eq(actorId), eq("BENCH_STATUS_CHANGE"), eq(employeeId), isNull(), eq("{}"));
    }

    @Test
    void start_keepsAnExplicitStartDate() {
        LocalDate explicit = LocalDate.of(2026, 9, 1);
        assertEquals(explicit, service.start(ACTOR_EMAIL, request(explicit)).getStartDate());
    }

    @Test
    void start_rejectsAnEmployeeAlreadyOnBench() {
        when(benchRepository.existsByEmployeeUserIdAndActiveTrue(employeeId)).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.start(ACTOR_EMAIL, request(null)));
        verify(benchRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void start_rejectsAnUnknownEmployee() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.start(ACTOR_EMAIL, request(null)));
    }

    @Test
    void end_closesTheEpisodeAndAuditsBeforeAndAfter() {
        BenchStatus bench = activeBench(employeeId, LocalDate.now().minusDays(10));
        when(benchRepository.findById(bench.getId())).thenReturn(Optional.of(bench));
        when(snapshotSerializer.toJson(any())).thenReturn("before", "after");

        EndBenchRequest req = new EndBenchRequest();
        req.setCloseReason(BenchCloseReason.RETURNED_TO_ACTIVE);

        BenchStatusResponse res = service.end(ACTOR_EMAIL, bench.getId(), req);

        assertFalse(res.isActive());
        assertEquals(LocalDate.now(), res.getEndDate());
        assertEquals(BenchCloseReason.RETURNED_TO_ACTIVE, res.getCloseReason());
        verify(auditService).log(actorId, "BENCH_STATUS_CHANGE", employeeId, "before", "after");
    }

    @Test
    void end_rejectsAnEndDateBeforeTheStartDate() {
        BenchStatus bench = activeBench(employeeId, LocalDate.of(2026, 9, 10));
        when(benchRepository.findById(bench.getId())).thenReturn(Optional.of(bench));
        EndBenchRequest req = new EndBenchRequest();
        req.setEndDate(LocalDate.of(2026, 9, 1));
        req.setCloseReason(BenchCloseReason.OTHER);

        assertThrows(IllegalArgumentException.class, () -> service.end(ACTOR_EMAIL, bench.getId(), req));
    }

    // ---- US-A06 ----

    @Test
    void end_withoutAllocationOrReason_isRejected() {
        BenchStatus bench = activeBench(employeeId, LocalDate.of(2026, 9, 1));
        when(benchRepository.findById(bench.getId())).thenReturn(Optional.of(bench));

        assertThrows(IllegalArgumentException.class, () -> service.end(ACTOR_EMAIL, bench.getId(), new EndBenchRequest()));
        assertTrue(bench.isActive());
        verifyNoInteractions(auditService);
    }

    @Test
    void end_withAnAllocation_linksItAndNeedsNoReason() {
        BenchStatus bench = activeBench(employeeId, LocalDate.now().minusDays(5));
        UUID allocationId = UUID.randomUUID();
        when(benchRepository.findById(bench.getId())).thenReturn(Optional.of(bench));
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.of(
                ProjectAllocation.builder().id(allocationId).employeeUserId(employeeId).build()));
        EndBenchRequest req = new EndBenchRequest();
        req.setAllocationId(allocationId);

        BenchStatusResponse res = service.end(ACTOR_EMAIL, bench.getId(), req);

        assertFalse(res.isActive());
        assertEquals(allocationId, res.getAllocationId());
        assertNull(res.getCloseReason());
    }

    @Test
    void end_rejectsAnAllocationThatBelongsToSomeoneElse() {
        BenchStatus bench = activeBench(employeeId, LocalDate.now().minusDays(5));
        UUID allocationId = UUID.randomUUID();
        when(benchRepository.findById(bench.getId())).thenReturn(Optional.of(bench));
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.of(
                ProjectAllocation.builder().id(allocationId).employeeUserId(UUID.randomUUID()).build()));
        EndBenchRequest req = new EndBenchRequest();
        req.setAllocationId(allocationId);

        assertThrows(IllegalArgumentException.class, () -> service.end(ACTOR_EMAIL, bench.getId(), req));
        assertTrue(bench.isActive());
    }

    @Test
    void end_rejectsAnUnknownAllocation() {
        BenchStatus bench = activeBench(employeeId, LocalDate.now().minusDays(5));
        UUID allocationId = UUID.randomUUID();
        when(benchRepository.findById(bench.getId())).thenReturn(Optional.of(bench));
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.empty());
        EndBenchRequest req = new EndBenchRequest();
        req.setAllocationId(allocationId);

        assertThrows(NoSuchElementException.class, () -> service.end(ACTOR_EMAIL, bench.getId(), req));
    }

    @Test
    void endForAllocation_clampsABackdatedEndToTheBenchStart_andAudits() {
        LocalDate benchStart = LocalDate.now().minusDays(3);
        BenchStatus bench = activeBench(employeeId, benchStart);
        UUID allocationId = UUID.randomUUID();
        when(benchRepository.findByEmployeeUserIdAndActiveTrue(employeeId)).thenReturn(Optional.of(bench));
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.of(
                ProjectAllocation.builder().id(allocationId).employeeUserId(employeeId).build()));

        BenchStatusResponse res = service.endForAllocation(actorId, employeeId, allocationId, benchStart.minusDays(10));

        assertEquals(benchStart, res.getEndDate());
        verify(auditService).log(eq(actorId), eq("BENCH_STATUS_CHANGE"), eq(employeeId), any(), any());
    }

    @Test
    void endForAllocation_failsWhenTheEmployeeIsNotOnBench() {
        when(benchRepository.findByEmployeeUserIdAndActiveTrue(employeeId)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> service.endForAllocation(actorId, employeeId, UUID.randomUUID(), LocalDate.now()));
    }

    @Test
    void end_rejectsAnAlreadyEndedEpisode() {
        BenchStatus bench = activeBench(employeeId, LocalDate.of(2026, 9, 1));
        bench.setActive(false);
        bench.setEndDate(LocalDate.of(2026, 9, 5));
        when(benchRepository.findById(bench.getId())).thenReturn(Optional.of(bench));

        assertThrows(IllegalStateException.class, () -> service.end(ACTOR_EMAIL, bench.getId(), null));
        verifyNoInteractions(auditService);
    }

    // ---- US-A02 ----

    @Test
    void teamBench_isEmptyWithoutDirectReports_andLoadsNoBenchRows() {
        when(directReportScopeService.directReportIds(ACTOR_EMAIL)).thenReturn(Set.of());

        assertTrue(service.teamBench(ACTOR_EMAIL).isEmpty());
        verifyNoInteractions(benchRepository);
    }

    @Test
    void teamBench_queriesOnlyTheCallersDirectReports() {
        UUID reportId = UUID.randomUUID();
        when(directReportScopeService.directReportIds(ACTOR_EMAIL)).thenReturn(Set.of(reportId));
        when(benchRepository.findByActiveTrueAndEmployeeUserIdIn(Set.of(reportId))).thenReturn(List.of());

        assertTrue(service.teamBench(ACTOR_EMAIL).isEmpty());
        verify(benchRepository).findByActiveTrueAndEmployeeUserIdIn(Set.of(reportId));
    }

    @Test
    void teamBench_showsReasonDaysAndLastProject_longestOnBenchFirst() {
        UUID shortId = UUID.randomUUID();
        UUID longId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        when(directReportScopeService.directReportIds(ACTOR_EMAIL)).thenReturn(Set.of(shortId, longId));
        when(benchRepository.findByActiveTrueAndEmployeeUserIdIn(any())).thenReturn(List.of(
                activeBench(shortId, LocalDate.now().minusDays(3)),
                activeBench(longId, LocalDate.now().minusDays(40))));
        when(employeeRepository.findAllById(any())).thenReturn(List.of(
                Employee.builder().userId(shortId).fullName("Short").build(),
                Employee.builder().userId(longId).fullName("Long").build()));
        when(allocationRepository.findLatestEndingForEmployees(any())).thenReturn(List.of(
                ProjectAllocation.builder().employeeUserId(longId).projectId(projectId).build()));
        when(projectRepository.findAllById(any())).thenReturn(List.of(
                Project.builder().id(projectId).name("Apollo").build()));

        List<TeamBenchResponse> rows = service.teamBench(ACTOR_EMAIL);

        assertEquals(2, rows.size());
        assertEquals("Long", rows.get(0).getEmployeeName());
        assertEquals(40, rows.get(0).getDaysOnBench());
        assertEquals("Apollo", rows.get(0).getLastProject());
        assertEquals(BenchReasonCode.PROJECT_ENDED, rows.get(0).getReasonCode());
        assertEquals("Short", rows.get(1).getEmployeeName());
        assertNull(rows.get(1).getLastProject());
    }

    private BenchStatus activeBench(UUID forEmployee, LocalDate start) {
        return BenchStatus.builder()
                .id(UUID.randomUUID())
                .employeeUserId(forEmployee)
                .reasonCode(BenchReasonCode.PROJECT_ENDED)
                .startDate(start)
                .build();
    }
}
