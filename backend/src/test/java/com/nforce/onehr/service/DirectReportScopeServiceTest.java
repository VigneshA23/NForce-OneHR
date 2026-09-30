package com.nforce.onehr.service;

import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The one authoritative "who is this caller's team" answer the My Team AI capability is built on
 * (ONEHR - NORA My Team access control). Every assertion here is really the same claim: whatever
 * the caller's role, whatever they say in a question, the answer is exactly and only
 * {@link EmployeeManagerHistoryRepository#findCurrentDirectReportIds}'s result for their own
 * account - never wider, never narrower.
 */
@ExtendWith(MockitoExtension.class)
class DirectReportScopeServiceTest {

    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository managerHistoryRepository;

    @InjectMocks private DirectReportScopeService scopeService;

    private final UUID actorId = UUID.randomUUID();
    private final UUID report1 = UUID.randomUUID();
    private final UUID report2 = UUID.randomUUID();
    private final UUID strangerId = UUID.randomUUID();
    private final String actorEmail = "actor@test.com";
    private Employee actor;

    @BeforeEach
    void setUp() {
        actor = Employee.builder().userId(actorId).fullName("Actor").build();
    }

    @Test
    void directReportIds_isExactlyWhatTheRepositoryReturnsForThisActor() {
        when(employeeRepository.findByUser_Email(actorEmail)).thenReturn(Optional.of(actor));
        when(managerHistoryRepository.findCurrentDirectReportIds(actorId)).thenReturn(List.of(report1, report2));

        assertThat(scopeService.directReportIds(actorEmail)).containsExactlyInAnyOrder(report1, report2);
    }

    @Test
    void directReportIds_isEmptyForAnActorWithNoReports_neverFallsBackToAnyoneElse() {
        when(employeeRepository.findByUser_Email(actorEmail)).thenReturn(Optional.of(actor));
        when(managerHistoryRepository.findCurrentDirectReportIds(actorId)).thenReturn(List.of());

        assertThat(scopeService.directReportIds(actorEmail)).isEmpty();
    }

    @Test
    void isDirectReport_trueOnlyForSomeoneGenuinelyInTheSet() {
        when(employeeRepository.findByUser_Email(actorEmail)).thenReturn(Optional.of(actor));
        when(managerHistoryRepository.findCurrentDirectReportIds(actorId)).thenReturn(List.of(report1));

        assertThat(scopeService.isDirectReport(actorEmail, report1)).isTrue();
        assertThat(scopeService.isDirectReport(actorEmail, strangerId)).isFalse();
    }

    @Test
    void isDirectReport_falseForANullId_neverThrows() {
        // Short-circuits on the null id before ever resolving the actor or their team - no stub
        // needed, and none should be reached.
        assertThat(scopeService.isDirectReport(actorEmail, null)).isFalse();
    }

    @Test
    void resolveActor_isBuiltEntirelyFromTheAuthenticatedEmail_neverFromAnythingElse() {
        when(employeeRepository.findByUser_Email(actorEmail)).thenReturn(Optional.of(actor));

        assertThat(scopeService.resolveActor(actorEmail)).isEqualTo(actor);
    }

    @Test
    void resolveActor_failsClosedForAnAccountWithNoEmployeeProfile() {
        when(employeeRepository.findByUser_Email(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> scopeService.resolveActor("nobody@test.com"))
                .isInstanceOf(IllegalArgumentException.class);
        // The failure must not silently resolve to "everyone" or "no restriction" - it must throw.
        assertThatThrownBy(() -> scopeService.directReportIds("nobody@test.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void directReports_returnsFullRecordsOnlyForTheCurrentReportSet_neverAnOrgWideLookup() {
        when(employeeRepository.findByUser_Email(actorEmail)).thenReturn(Optional.of(actor));
        when(managerHistoryRepository.findCurrentDirectReportIds(actorId)).thenReturn(List.of(report1));
        Employee report1Employee = Employee.builder().userId(report1).fullName("Report One").build();
        when(employeeRepository.findAllById(Set.of(report1))).thenReturn(List.of(report1Employee));

        assertThat(scopeService.directReports(actorEmail)).containsExactly(report1Employee);
    }

    @Test
    void directReports_skipsTheLookupEntirelyWhenThereAreNoReports() {
        when(employeeRepository.findByUser_Email(actorEmail)).thenReturn(Optional.of(actor));
        when(managerHistoryRepository.findCurrentDirectReportIds(actorId)).thenReturn(List.of());

        assertThat(scopeService.directReports(actorEmail)).isEmpty();
    }

    @Test
    void anAdminRoleOnTheActorChangesNothing_thisServiceHasNoRoleBranch() {
        // DirectReportScopeService deliberately never reads Employee.getUser().getRoles() at all -
        // unlike AttendancePenaltyService.resolveScopeIds or ReportsService.isOrgWideReporter, an
        // admin role held by the caller cannot widen what this class returns, because nothing here
        // even looks at it. Verified by giving the actor an admin-shaped User and getting the same
        // answer as a role-less one.
        Employee adminActor = Employee.builder().userId(actorId).fullName("Admin Actor")
                .user(com.nforce.onehr.entity.User.builder().id(actorId)
                        .roles(Set.of(com.nforce.onehr.entity.Role.builder().id(1).code("SUPER_ADMIN").displayName("Super Admin").build()))
                        .build())
                .build();
        when(employeeRepository.findByUser_Email(actorEmail)).thenReturn(Optional.of(adminActor));
        when(managerHistoryRepository.findCurrentDirectReportIds(actorId)).thenReturn(List.of(report1));

        assertThat(scopeService.directReportIds(actorEmail)).containsExactly(report1);
    }
}
