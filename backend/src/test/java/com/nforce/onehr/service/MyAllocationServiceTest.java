package com.nforce.onehr.service;

import com.nforce.onehr.dto.allocation.MyAllocationResponse;
import com.nforce.onehr.dto.allocation.MyAllocationResponse.Status;
import com.nforce.onehr.entity.Client;
import com.nforce.onehr.entity.Project;
import com.nforce.onehr.entity.ProjectAllocation;
import com.nforce.onehr.entity.ProjectStatus;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.ClientRepository;
import com.nforce.onehr.repository.ProjectAllocationRepository;
import com.nforce.onehr.repository.ProjectRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * US-B08: an employee sees only their OWN current and upcoming allocations — joined with project and
 * client, current before upcoming, ended ones (judged by actualEndDate when released early) excluded,
 * missing project/client tolerated, and "today" taken in the business timezone.
 */
@ExtendWith(MockitoExtension.class)
class MyAllocationServiceTest {

    @Mock private ProjectAllocationRepository allocationRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private ClientRepository clientRepository;
    @Mock private UserRepository userRepository;
    @Mock private AttendanceRulesService attendanceRulesService;

    private MyAllocationService service;

    private final UUID me = UUID.randomUUID();
    private final LocalDate today = LocalDate.of(2026, 11, 16);
    private final UUID clientId = UUID.randomUUID();
    private final Project apollo = project("Apollo", clientId);
    private final Client acme = Client.builder().id(clientId).name("Acme Corp").build();

    @BeforeEach
    void setUp() {
        service = new MyAllocationService(allocationRepository, projectRepository, clientRepository,
                userRepository, attendanceRulesService);
        User user = new User();
        user.setId(me);
        lenient().when(userRepository.findByEmail("emp@x.com")).thenReturn(Optional.of(user));
    }

    // ── categorisation ──

    @Test
    void oneCurrentAllocation_isReturnedWithProjectClientPercentAndDates() {
        stubAllocations(alloc(apollo, 60, "2026-10-01", "2027-03-31", null));
        stubLookups(apollo);

        List<MyAllocationResponse> result = service.myCurrentAndUpcoming("emp@x.com", today);

        assertEquals(1, result.size());
        MyAllocationResponse r = result.get(0);
        assertEquals("Apollo", r.getProjectName());
        assertEquals("Acme Corp", r.getClientName());
        assertEquals(60, r.getCapacityPercent());
        assertEquals(LocalDate.of(2026, 10, 1), r.getStartDate());
        assertEquals(LocalDate.of(2027, 3, 31), r.getEndDate());
        assertEquals(Status.CURRENT, r.getStatus());
    }

    @Test
    void multipleCurrentAllocations_areAllReturned() {
        Project borealis = project("Borealis", clientId);
        stubAllocations(alloc(apollo, 50, "2026-10-01", "2027-03-31", null),
                alloc(borealis, 50, "2026-11-01", "2027-01-31", null));
        stubLookups(apollo, borealis);

        List<MyAllocationResponse> result = service.myCurrentAndUpcoming("emp@x.com", today);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(r -> r.getStatus() == Status.CURRENT));
    }

    @Test
    void upcomingAllocation_isLabelledUpcoming() {
        stubAllocations(alloc(apollo, 40, "2026-12-01", "2027-02-28", null));
        stubLookups(apollo);

        assertEquals(Status.UPCOMING, service.myCurrentAndUpcoming("emp@x.com", today).get(0).getStatus());
    }

    @Test
    void currentAndUpcoming_currentComesFirst_thenByStartDate() {
        Project borealis = project("Borealis", clientId);
        Project cygnus = project("Cygnus", clientId);
        stubAllocations(
                alloc(cygnus, 20, "2027-02-01", "2027-06-30", null),    // upcoming, later
                alloc(borealis, 30, "2026-12-01", "2027-01-31", null),  // upcoming, sooner
                alloc(apollo, 50, "2026-10-01", "2027-03-31", null));   // current
        stubLookups(apollo, borealis, cygnus);

        List<MyAllocationResponse> result = service.myCurrentAndUpcoming("emp@x.com", today);

        assertEquals(List.of("Apollo", "Borealis", "Cygnus"), result.stream().map(MyAllocationResponse::getProjectName).toList());
        assertEquals(List.of(Status.CURRENT, Status.UPCOMING, Status.UPCOMING), result.stream().map(MyAllocationResponse::getStatus).toList());
    }

    @Test
    void noAllocations_returnsEmptyList_andSkipsTheProjectAndClientLookups() {
        stubAllocations();

        assertTrue(service.myCurrentAndUpcoming("emp@x.com", today).isEmpty());

        verifyNoInteractions(projectRepository, clientRepository);
    }

    // ── date boundaries ──

    @Test
    void startingToday_isCurrent_endingToday_isStillCurrent() {
        stubAllocations(alloc(apollo, 100, "2026-11-16", "2026-11-16", null));
        stubLookups(apollo);

        assertEquals(Status.CURRENT, service.myCurrentAndUpcoming("emp@x.com", today).get(0).getStatus());
    }

    @Test
    void startingTomorrow_isUpcoming() {
        stubAllocations(alloc(apollo, 100, "2026-11-17", "2026-12-31", null));
        stubLookups(apollo);

        assertEquals(Status.UPCOMING, service.myCurrentAndUpcoming("emp@x.com", today).get(0).getStatus());
    }

    @Test
    void allocationThatEndedYesterday_isExcluded_evenIfTheRepositoryReturnsIt() {
        stubAllocations(alloc(apollo, 100, "2026-01-01", "2026-11-15", null));

        assertTrue(service.myCurrentAndUpcoming("emp@x.com", today).isEmpty());
    }

    @Test
    void allocationReleasedEarly_isExcluded_andOneStillRunningShowsTheActualEnd() {
        Project borealis = project("Borealis", clientId);
        stubAllocations(
                alloc(apollo, 50, "2026-01-01", "2027-03-31", "2026-11-10"),   // released before today
                alloc(borealis, 50, "2026-01-01", "2027-03-31", "2026-12-15")); // released, ends in future
        stubLookups(borealis);

        List<MyAllocationResponse> result = service.myCurrentAndUpcoming("emp@x.com", today);

        assertEquals(1, result.size());
        assertEquals("Borealis", result.get(0).getProjectName());
        assertEquals(LocalDate.of(2026, 12, 15), result.get(0).getEndDate());
    }

    // ── the allocation follows its project (active / on hold / closed) ──

    @Test
    void closedProject_stillAppears_labelledClosed() {
        Project closed = project("AI", clientId, ProjectStatus.CLOSED, LocalDate.of(2027, 6, 30));
        stubAllocations(alloc(closed, 100, "2026-11-16", "2026-11-30", null));
        stubLookups(closed);

        List<MyAllocationResponse> result = service.myCurrentAndUpcoming("emp@x.com", today);

        assertEquals(1, result.size());
        assertEquals(ProjectStatus.CLOSED, result.get(0).getProjectStatus());
    }

    // ── the dates shown follow the project's current dates (what the manager sees) ──

    @Test
    void closedProject_editedToEndBeforeTheAllocationStarts_showsTheProjectsOwnDates() {
        // The reported case: the AI project was edited to 2026-11-10 .. 2026-11-15 and closed, but the allocation
        // (made earlier) still reads 16 Nov .. 30 Nov. The employee must see the project dates the manager sees.
        Project ai = projectWithDates("AI", ProjectStatus.CLOSED, "2026-11-10", "2026-11-15");
        stubAllocations(alloc(ai, 100, "2026-11-16", "2026-11-30", null));
        stubLookups(ai);

        MyAllocationResponse r = service.myCurrentAndUpcoming("emp@x.com", today).get(0);

        assertEquals(LocalDate.of(2026, 11, 10), r.getStartDate());
        assertEquals(LocalDate.of(2026, 11, 15), r.getEndDate());
        assertEquals(ProjectStatus.CLOSED, r.getProjectStatus());
    }

    @Test
    void projectEndingBeforeTheAllocationEnds_showsTheProjectsEnd_open_or_closed() {
        Project open = projectWithDates("Open", ProjectStatus.ACTIVE, "2026-09-01", "2026-12-15");
        Project closed = projectWithDates("Closed", ProjectStatus.CLOSED, "2026-09-01", "2026-12-15");
        stubAllocations(alloc(open, 50, "2026-10-01", "2027-03-31", null), alloc(closed, 50, "2026-10-01", "2027-03-31", null));
        stubLookups(open, closed);

        List<MyAllocationResponse> result = service.myCurrentAndUpcoming("emp@x.com", today);

        assertTrue(result.stream().allMatch(r -> r.getEndDate().equals(LocalDate.of(2026, 12, 15))));
        assertTrue(result.stream().allMatch(r -> r.getStartDate().equals(LocalDate.of(2026, 10, 1))));
    }

    @Test
    void projectStartingAfterTheAllocationStarts_clipsTheShownStart() {
        Project p = projectWithDates("AI", ProjectStatus.ACTIVE, "2026-11-01", "2027-03-31");
        stubAllocations(alloc(p, 100, "2026-10-01", "2027-03-31", null));
        stubLookups(p);

        assertEquals(LocalDate.of(2026, 11, 1), service.myCurrentAndUpcoming("emp@x.com", today).get(0).getStartDate());
    }

    @Test
    void projectWindowWiderThanTheAllocation_keepsTheAllocationsOwnDates() {
        Project p = projectWithDates("AI", ProjectStatus.ACTIVE, "2026-01-01", "2027-12-31");
        stubAllocations(alloc(p, 100, "2026-10-01", "2027-03-31", null));
        stubLookups(p);

        MyAllocationResponse r = service.myCurrentAndUpcoming("emp@x.com", today).get(0);

        assertEquals(LocalDate.of(2026, 10, 1), r.getStartDate());
        assertEquals(LocalDate.of(2027, 3, 31), r.getEndDate());
    }

    @Test
    void projectWithNoEndDate_keepsTheAllocationsOwnDates() {
        Project ongoing = project("AI", clientId, ProjectStatus.ACTIVE, null);
        stubAllocations(alloc(ongoing, 100, "2026-10-01", "2027-03-31", null));
        stubLookups(ongoing);

        MyAllocationResponse r = service.myCurrentAndUpcoming("emp@x.com", today).get(0);

        assertEquals(LocalDate.of(2026, 10, 1), r.getStartDate());
        assertEquals(LocalDate.of(2027, 3, 31), r.getEndDate());
    }

    @Test
    void allocationReleasedEarly_isClippedByTheEarlierOfItsActualEndAndTheProjectEnd() {
        Project p = projectWithDates("AI", ProjectStatus.ACTIVE, "2026-09-01", "2027-03-31");
        stubAllocations(alloc(p, 100, "2026-10-01", "2027-03-31", "2026-12-20"));
        stubLookups(p);

        assertEquals(LocalDate.of(2026, 12, 20), service.myCurrentAndUpcoming("emp@x.com", today).get(0).getEndDate());
    }

    @Test
    void listing_andCurrentVsUpcoming_stillFollowTheAllocationsOwnDates_notTheShownRange() {
        // Project edited to end before the allocation starts: the shown dates are the project's, but the
        // allocation is still listed, and is still UPCOMING because its own start is in the future.
        Project ai = projectWithDates("AI", ProjectStatus.CLOSED, "2026-11-10", "2026-11-15");
        stubAllocations(alloc(ai, 100, "2026-12-01", "2026-12-31", null));
        stubLookups(ai);

        MyAllocationResponse r = service.myCurrentAndUpcoming("emp@x.com", today).get(0);

        assertEquals(Status.UPCOMING, r.getStatus());
        assertEquals(LocalDate.of(2026, 11, 15), r.getEndDate());
    }

    @Test
    void closedProject_withAnUpcomingAllocation_alsoAppearsClosed() {
        Project closed = project("AI", clientId, ProjectStatus.CLOSED, null);
        stubAllocations(alloc(closed, 100, "2026-12-01", "2027-02-28", null));
        stubLookups(closed);

        MyAllocationResponse r = service.myCurrentAndUpcoming("emp@x.com", today).get(0);

        assertEquals(ProjectStatus.CLOSED, r.getProjectStatus());
        assertEquals(Status.UPCOMING, r.getStatus());
    }

    @Test
    void activeProjectsComeFirst_thenOnHold_thenClosed_regardlessOfDates() {
        Project closed = project("Zeta", clientId, ProjectStatus.CLOSED, null);
        Project paused = project("Yankee", clientId, ProjectStatus.ON_HOLD, null);
        Project active = project("Xray", clientId, ProjectStatus.ACTIVE, null);
        stubAllocations(
                alloc(closed, 30, "2026-10-01", "2027-03-31", null),   // closed, earliest start
                alloc(paused, 30, "2026-10-15", "2027-03-31", null),   // on hold
                alloc(active, 40, "2026-12-01", "2027-06-30", null));  // active but UPCOMING
        stubLookups(closed, paused, active);

        List<MyAllocationResponse> result = service.myCurrentAndUpcoming("emp@x.com", today);

        assertEquals(List.of("Xray", "Yankee", "Zeta"), result.stream().map(MyAllocationResponse::getProjectName).toList());
        assertEquals(List.of(ProjectStatus.ACTIVE, ProjectStatus.ON_HOLD, ProjectStatus.CLOSED),
                result.stream().map(MyAllocationResponse::getProjectStatus).toList());
    }

    @Test
    void withinTheSameProjectStatus_currentComesBeforeUpcoming() {
        Project a1 = project("Alpha", clientId, ProjectStatus.ACTIVE, null);
        Project a2 = project("Bravo", clientId, ProjectStatus.ACTIVE, null);
        stubAllocations(alloc(a2, 50, "2026-12-01", "2027-03-31", null),  // upcoming
                alloc(a1, 50, "2026-10-01", "2027-03-31", null));         // current
        stubLookups(a1, a2);

        List<MyAllocationResponse> result = service.myCurrentAndUpcoming("emp@x.com", today);

        assertEquals(List.of("Alpha", "Bravo"), result.stream().map(MyAllocationResponse::getProjectName).toList());
    }

    @Test
    void activeProjectPastItsEndDate_countsAsClosed_beforeTheDailyAutoCloseRuns() {
        // Status is still ACTIVE (the daily sweep has not flipped it yet) but its end date is already in the past.
        Project ended = project("AI", clientId, ProjectStatus.ACTIVE, LocalDate.of(2026, 11, 10));
        stubAllocations(alloc(ended, 100, "2026-10-01", "2027-03-31", null));
        stubLookups(ended);

        assertEquals(ProjectStatus.CLOSED, service.myCurrentAndUpcoming("emp@x.com", today).get(0).getProjectStatus());
    }

    @Test
    void activeProjectEndingToday_isStillActive() {
        Project p = project("AI", clientId, ProjectStatus.ACTIVE, today);
        stubAllocations(alloc(p, 100, "2026-10-01", "2027-03-31", null));
        stubLookups(p);

        assertEquals(ProjectStatus.ACTIVE, service.myCurrentAndUpcoming("emp@x.com", today).get(0).getProjectStatus());
    }

    @Test
    void onHoldProject_staysVisible_andCarriesItsStatus() {
        Project paused = project("AI", clientId, ProjectStatus.ON_HOLD, null);
        stubAllocations(alloc(paused, 100, "2026-10-01", "2027-03-31", null));
        stubLookups(paused);

        assertEquals(ProjectStatus.ON_HOLD, service.myCurrentAndUpcoming("emp@x.com", today).get(0).getProjectStatus());
    }

    @Test
    void reopenedProject_isShownActiveAgain() {
        Project p = project("AI", clientId, ProjectStatus.ACTIVE, null);   // was CLOSED, set back to ACTIVE
        stubAllocations(alloc(p, 100, "2026-11-16", "2026-11-30", null));
        stubLookups(p);

        assertEquals(ProjectStatus.ACTIVE, service.myCurrentAndUpcoming("emp@x.com", today).get(0).getProjectStatus());
    }

    @Test
    void closedProjectsAllocationThatAlreadyEnded_isStillExcluded() {
        // "Closed" does not resurrect history: an allocation that finished before today stays out.
        Project closed = project("AI", clientId, ProjectStatus.CLOSED, null);
        stubAllocations(alloc(closed, 100, "2026-01-01", "2026-11-15", null));

        assertTrue(service.myCurrentAndUpcoming("emp@x.com", today).isEmpty());
    }

    @Test
    void projectRowMissing_sortsWithTheActiveOnes_andHasNoStatus() {
        Project closed = project("Closed One", clientId, ProjectStatus.CLOSED, null);
        ProjectAllocation orphan = alloc(apollo, 30, "2026-10-01", "2027-03-31", null);   // project row will not be returned
        stubAllocations(alloc(closed, 30, "2026-10-01", "2027-03-31", null), orphan);
        when(projectRepository.findAllById(anyCollection())).thenReturn(List.of(closed));
        lenient().when(clientRepository.findAllById(anyCollection())).thenReturn(List.of(acme));

        List<MyAllocationResponse> result = service.myCurrentAndUpcoming("emp@x.com", today);

        assertNull(result.get(0).getProjectStatus());
        assertEquals(ProjectStatus.CLOSED, result.get(1).getProjectStatus());
    }

    // ── ownership ──

    @Test
    void queriesOnlyTheCallersOwnAllocations() {
        stubAllocations();

        service.myCurrentAndUpcoming("emp@x.com", today);

        verify(allocationRepository).findCurrentAndUpcoming(me, today);
        verify(allocationRepository, never()).findAll();
        verify(allocationRepository, never()).findByEmployeeUserIdIn(anyCollection());
    }

    @Test
    void unknownCaller_isRejected_beforeAnyAllocationRead() {
        when(userRepository.findByEmail("ghost@x.com")).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.myCurrentAndUpcoming("ghost@x.com", today));

        verifyNoInteractions(allocationRepository);
    }

    @Test
    void serviceNeverWritesAnything() {
        stubAllocations(alloc(apollo, 50, "2026-10-01", "2027-03-31", null));
        stubLookups(apollo);

        service.myCurrentAndUpcoming("emp@x.com", today);

        verify(allocationRepository, never()).save(any());
        verify(allocationRepository, never()).saveAll(any());
        verify(allocationRepository, never()).delete(any());
        verify(allocationRepository, never()).deleteById(any());
    }

    // ── missing relationship data ──

    @Test
    void projectWithoutClient_hasNullClientName_notAnError() {
        Project internal = project("Internal Tools", null);
        stubAllocations(alloc(internal, 30, "2026-10-01", "2027-03-31", null));
        stubLookups(internal);

        MyAllocationResponse r = service.myCurrentAndUpcoming("emp@x.com", today).get(0);

        assertEquals("Internal Tools", r.getProjectName());
        assertNull(r.getClientName());
    }

    @Test
    void clientRowMissing_hasNullClientName_notAnError() {
        UUID orphanClient = UUID.randomUUID();
        Project orphan = project("Orphaned", orphanClient);
        stubAllocations(alloc(orphan, 30, "2026-10-01", "2027-03-31", null));
        when(projectRepository.findAllById(anyCollection())).thenReturn(List.of(orphan));
        when(clientRepository.findAllById(anyCollection())).thenReturn(List.of());

        assertNull(service.myCurrentAndUpcoming("emp@x.com", today).get(0).getClientName());
    }

    @Test
    void projectRowMissing_hasNullNames_notAnError() {
        stubAllocations(alloc(apollo, 30, "2026-10-01", "2027-03-31", null));
        when(projectRepository.findAllById(anyCollection())).thenReturn(List.of());

        MyAllocationResponse r = service.myCurrentAndUpcoming("emp@x.com", today).get(0);

        assertNull(r.getProjectName());
        assertNull(r.getClientName());
        assertEquals(30, r.getCapacityPercent());
    }

    // ── timezone ──

    @Test
    void todayIsTakenInTheBusinessTimezone() {
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        when(attendanceRulesService.getDefaultZoneId()).thenReturn(zone);
        when(allocationRepository.findCurrentAndUpcoming(eq(me), any())).thenReturn(List.of());

        service.myCurrentAndUpcoming("emp@x.com");

        verify(allocationRepository).findCurrentAndUpcoming(me, LocalDate.now(zone));
    }

    // ── helpers ──

    private void stubAllocations(ProjectAllocation... allocations) {
        when(allocationRepository.findCurrentAndUpcoming(eq(me), any())).thenReturn(List.of(allocations));
    }

    private void stubLookups(Project... projects) {
        when(projectRepository.findAllById(anyCollection())).thenReturn(List.of(projects));
        lenient().when(clientRepository.findAllById(anyCollection())).thenReturn(List.of(acme));
    }

    private Project project(String name, UUID client) {
        return Project.builder().id(UUID.randomUUID()).name(name).code(name.toUpperCase()).clientId(client).build();
    }

    private Project projectWithDates(String name, ProjectStatus status, String start, String end) {
        return Project.builder().id(UUID.randomUUID()).name(name).code(name.toUpperCase()).clientId(clientId)
                .status(status).startDate(LocalDate.parse(start)).endDate(LocalDate.parse(end)).build();
    }

    private Project project(String name, UUID client, ProjectStatus status, LocalDate endDate) {
        return Project.builder().id(UUID.randomUUID()).name(name).code(name.toUpperCase()).clientId(client)
                .status(status).endDate(endDate).build();
    }

    private ProjectAllocation alloc(Project project, int percent, String start, String end, String actualEnd) {
        return ProjectAllocation.builder().id(UUID.randomUUID()).employeeUserId(me).projectId(project.getId())
                .capacityPercent(percent).startDate(LocalDate.parse(start)).endDate(LocalDate.parse(end))
                .actualEndDate(actualEnd == null ? null : LocalDate.parse(actualEnd)).build();
    }
}
