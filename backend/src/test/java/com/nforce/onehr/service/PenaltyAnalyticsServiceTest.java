package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.PenaltyAnalyticsResponse;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.AttendancePenaltyStatus;
import com.nforce.onehr.entity.Department;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.ExceptionType;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** PN-06: penalty analytics - grouping, status handling, scope and validation. */
@ExtendWith(MockitoExtension.class)
class PenaltyAnalyticsServiceTest {

    @Mock private AttendancePenaltyService attendancePenaltyService;
    @Mock private AttendancePenaltyRepository attendancePenaltyRepository;
    @Mock private EmployeeRepository employeeRepository;

    private PenaltyAnalyticsService service;

    private final String actor = "hr@test.com";
    private final UUID asha = UUID.randomUUID();
    private final UUID ravi = UUID.randomUUID();
    private final LocalDate from = LocalDate.of(2026, 9, 1);
    private final LocalDate to = LocalDate.of(2026, 11, 30);

    @BeforeEach
    void setUp() {
        service = new PenaltyAnalyticsService(attendancePenaltyService, attendancePenaltyRepository, employeeRepository);
        lenient().when(attendancePenaltyService.viewScopeIds(actor)).thenReturn(Set.of(asha, ravi));
        lenient().when(attendancePenaltyService.hasOrganisationViewScope(actor)).thenReturn(true);
        lenient().when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(
                emp(asha, "Asha Rao", "NF-1", "Engineering"), emp(ravi, "Ravi Kumar", "NF-2", "Support")));
    }

    private Employee emp(UUID id, String name, String code, String dept) {
        return Employee.builder().userId(id).fullName(name).employeeCode(code)
                .department(Department.builder().name(dept).build()).build();
    }

    private AttendancePenalty penalty(UUID emp, String type, String status, String days, LocalDate date) {
        return AttendancePenalty.builder().employeeUserId(emp).discrepancyType(type).status(status)
                .deductionDays(new BigDecimal(days)).incidentDate(date).build();
    }

    @SuppressWarnings("unchecked")
    private void givenPenalties(AttendancePenalty... rows) {
        lenient().when(attendancePenaltyRepository.findAll(any(Specification.class))).thenReturn(List.of(rows));
    }

    @Test
    void groupsByRuleDepartmentMonthAndEmployee_countingDeductionOnlyForStandingPenalties() {
        givenPenalties(
                penalty(asha, ExceptionType.LATE_ARRIVAL, AttendancePenaltyStatus.PENDING_REVIEW, "0.5", LocalDate.of(2026, 9, 10)),
                penalty(asha, ExceptionType.LATE_ARRIVAL, AttendancePenaltyStatus.PENDING_REVIEW, "0.5", LocalDate.of(2026, 10, 5)),
                penalty(asha, ExceptionType.MISSING_PUNCH, AttendancePenaltyStatus.CANCELLED, "1", LocalDate.of(2026, 10, 7)),
                penalty(ravi, ExceptionType.EARLY_DEPARTURE, AttendancePenaltyStatus.REVERSED, "0.5", LocalDate.of(2026, 11, 3)),
                penalty(ravi, ExceptionType.LATE_ARRIVAL, AttendancePenaltyStatus.PENDING_REVIEW, "0.25", LocalDate.of(2026, 11, 4)));

        PenaltyAnalyticsResponse r = service.analyse(actor, from, to, null, null, null);

        assertEquals("ORGANISATION", r.getScope());
        assertEquals(5, r.getSummary().getTotal());
        assertEquals(3, r.getSummary().getStanding());
        assertEquals(1, r.getSummary().getCancelled());
        assertEquals(1, r.getSummary().getReversed());
        // 0.5 + 0.5 + 0.25: the cancelled 1.0 and the reversed 0.5 cost nobody anything
        assertEquals(0, new BigDecimal("1.25").compareTo(r.getSummary().getDeductionDays()));
        assertEquals(2, r.getSummary().getEmployeesAffected());

        assertEquals("LATE_ARRIVAL", r.getByRule().get(0).getKey(), "most frequent rule first");
        assertEquals(3, r.getByRule().get(0).getTotal());
        assertEquals("Late Arrival", r.getByRule().get(0).getLabel());

        assertEquals(2, r.getByDepartment().size());
        assertEquals("Engineering", r.getByDepartment().get(0).getKey());
        assertEquals(3, r.getByDepartment().get(0).getTotal());

        assertEquals(List.of("2026-09", "2026-10", "2026-11"), r.getByMonth().stream().map(b -> b.getKey()).toList());
        assertEquals(List.of(1, 2, 2), r.getByMonth().stream().map(b -> b.getTotal()).toList());

        // worst first: Asha stands on 1.0 day, Ravi on 0.25
        assertEquals("Asha Rao", r.getByEmployee().get(0).getFullName());
        assertEquals("Late Arrival", r.getByEmployee().get(0).getTopRule());
        assertEquals(List.of("Engineering", "Support"), r.getDepartments());
    }

    @Test
    void aQuietMonthStillAppearsInTheTrendAsZero() {
        givenPenalties(penalty(asha, ExceptionType.LATE_ARRIVAL, AttendancePenaltyStatus.PENDING_REVIEW, "0.5", LocalDate.of(2026, 9, 10)),
                penalty(asha, ExceptionType.LATE_ARRIVAL, AttendancePenaltyStatus.PENDING_REVIEW, "0.5", LocalDate.of(2026, 11, 10)));

        PenaltyAnalyticsResponse r = service.analyse(actor, from, to, null, null, null);

        assertEquals(List.of(1, 0, 1), r.getByMonth().stream().map(b -> b.getTotal()).toList());
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getByMonth().get(1).getDeductionDays()));
    }

    @Test
    void noPenalties_returnsAnEmptyButWellFormedReport() {
        givenPenalties();

        PenaltyAnalyticsResponse r = service.analyse(actor, from, to, null, null, null);

        assertEquals(0, r.getSummary().getTotal());
        assertEquals(0, r.getSummary().getEmployeesAffected());
        assertTrue(r.getByRule().isEmpty());
        assertTrue(r.getByEmployee().isEmpty());
        assertEquals(3, r.getByMonth().size());
    }

    @Test
    void departmentFilter_onlyQueriesThatDepartmentsEmployees_butStillListsEveryDepartmentForTheDropdown() {
        givenPenalties(penalty(ravi, ExceptionType.LATE_ARRIVAL, AttendancePenaltyStatus.PENDING_REVIEW, "0.5", LocalDate.of(2026, 10, 1)));

        PenaltyAnalyticsResponse r = service.analyse(actor, from, to, null, "support", null);

        assertEquals(List.of("Engineering", "Support"), r.getDepartments());
        assertEquals(1, r.getSummary().getTotal());
    }

    @Test
    void aDepartmentNobodyIsIn_runsNoPenaltyQueryAtAll() {
        PenaltyAnalyticsResponse r = service.analyse(actor, from, to, null, "Finance", null);

        assertEquals(0, r.getSummary().getTotal());
        verify(attendancePenaltyRepository, never()).findAll(any(Specification.class));
    }

    @Test
    void manager_isReportedAsTeamScope_andOnlyTheirReportsAreEverLoaded() {
        org.mockito.Mockito.when(attendancePenaltyService.hasOrganisationViewScope(actor)).thenReturn(false);
        org.mockito.Mockito.when(attendancePenaltyService.viewScopeIds(actor)).thenReturn(Set.of(ravi));
        givenPenalties();

        PenaltyAnalyticsResponse r = service.analyse(actor, from, to, null, null, null);

        assertEquals("TEAM", r.getScope());
        org.mockito.ArgumentCaptor<java.util.Collection<UUID>> ids = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(employeeRepository).findAllByIdWithScheduleDetails(ids.capture());
        assertEquals(Set.of(ravi), Set.copyOf(ids.getValue()));
    }

    @Test
    void anEmployeeWithNoDepartment_isGroupedAsUnassigned() {
        UUID loner = UUID.randomUUID();
        org.mockito.Mockito.when(attendancePenaltyService.viewScopeIds(actor)).thenReturn(Set.of(loner));
        org.mockito.Mockito.when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(
                Employee.builder().userId(loner).fullName("No Dept").employeeCode("NF-9").build()));
        givenPenalties(penalty(loner, ExceptionType.NO_ATTENDANCE, AttendancePenaltyStatus.PENDING_REVIEW, "1", LocalDate.of(2026, 10, 1)));

        PenaltyAnalyticsResponse r = service.analyse(actor, from, to, null, null, null);

        assertEquals("Unassigned", r.getByDepartment().get(0).getKey());
    }

    @Test
    void invalidRanges_areRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.analyse(actor, to, from, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.analyse(actor, null, to, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.analyse(actor, LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1), null, null, null));
    }

    @Test
    void topRule_fallsBackToAllPenaltiesWhenNoneStand() {
        givenPenalties(
                penalty(asha, ExceptionType.MISSING_PUNCH, AttendancePenaltyStatus.CANCELLED, "1", LocalDate.of(2026, 10, 1)),
                penalty(asha, ExceptionType.MISSING_PUNCH, AttendancePenaltyStatus.REVERSED, "1", LocalDate.of(2026, 10, 2)));

        PenaltyAnalyticsResponse r = service.analyse(actor, from, to, null, null, null);

        assertEquals("Missing Logs", r.getByEmployee().get(0).getTopRule());
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getByEmployee().get(0).getDeductionDays()));
    }
}
