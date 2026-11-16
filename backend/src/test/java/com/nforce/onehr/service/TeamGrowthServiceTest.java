package com.nforce.onehr.service;

import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.security.SeedMatrix;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Viewing someone's growth: their direct manager always; anyone else only with talent_skills:VIEW:ALL
 * (HR_ADMIN/SUPER_ADMIN in the seed, or a custom role granted it) — formerly a HR_ADMIN/SUPER_ADMIN role-code check.
 */
@ExtendWith(MockitoExtension.class)
class TeamGrowthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private DirectReportScopeService directReportScopeService;
    @Mock private EmployeeSkillService skillService;
    @Mock private EmployeeCertificateService certificateService;
    @Mock private EmployeeLearningEntryService learningEntryService;

    private final EffectivePermissionService effectivePermissionService = SeedMatrix.permissionService(Map.of(
            "CUSTOM_TALENT_VIEWER", Map.of("talent_skills:VIEW", "ALL"),
            "CUSTOM_OTHER", Map.of("talent_skills:CONFIGURE", "ALL"),
            "CUSTOM_TEAM_SKILLS", Map.of("skill_ratings:VIEW", "TEAM")));

    private TeamGrowthService service;
    private final UUID stranger = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TeamGrowthService(userRepository, employeeRepository, directReportScopeService, skillService,
                certificateService, learningEntryService, effectivePermissionService);
    }

    private String actor(String code) {
        Role r = code.startsWith("CUSTOM_") ? Role.builder().id(99).code(code).displayName(code).build() : SeedMatrix.role(code);
        User u = User.builder().id(UUID.randomUUID()).roles(new java.util.HashSet<>(java.util.List.of(SeedMatrix.role("EMPLOYEE"), r))).build();
        String email = code + "@t";
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(u));
        return email;
    }

    @Test
    void talentViewAllHolders_seeAnyonesGrowth_withoutAHierarchyCheck() {
        for (String code : List.of("HR_ADMIN", "SUPER_ADMIN", "CUSTOM_TALENT_VIEWER")) {
            assertEquals(stranger, service.getEmployeeGrowth(actor(code), stranger).getEmployeeUserId(), code);
        }
        verifyNoInteractions(directReportScopeService);
    }

    @Test
    void directReportsAreViewable_onlyWithTheTeamPermission_aReportingLineAloneIsNotEnough() {
        // Manager (seeded skill_ratings:VIEW:TEAM) and a custom role granted it — no MANAGER role needed.
        for (String code : List.of("MANAGER", "CUSTOM_TEAM_SKILLS")) {
            String email = actor(code);
            when(directReportScopeService.isDirectReport(email, stranger)).thenReturn(true);
            assertEquals(stranger, service.getEmployeeGrowth(email, stranger).getEmployeeUserId(), code);
        }
        // Holds no team permission although they ARE the person's direct manager: denied.
        String plain = actor("CUSTOM_OTHER");
        lenient().when(directReportScopeService.isDirectReport(plain, stranger)).thenReturn(true);
        assertThrows(AccessDeniedException.class, () -> service.getEmployeeGrowth(plain, stranger));
        String employee = actor("EMPLOYEE");
        lenient().when(directReportScopeService.isDirectReport(employee, stranger)).thenReturn(true);
        assertThrows(AccessDeniedException.class, () -> service.getEmployeeGrowth(employee, stranger));
    }

    @Test
    void teamSummaryList_needsTheTeamPermission_aReportingLineAloneIsNotEnough() {
        for (String code : List.of("EMPLOYEE", "CUSTOM_OTHER")) {
            String email = actor(code);
            lenient().when(directReportScopeService.directReports(email)).thenReturn(List.of());
            assertThrows(AccessDeniedException.class, () -> service.listTeamGrowthSummaries(email), code);
        }
        // Holders of the team permission (seeded MANAGER/HR_ADMIN, or a custom role) are allowed.
        for (String code : List.of("MANAGER", "HR_ADMIN", "CUSTOM_TEAM_SKILLS")) {
            String email = actor(code);
            when(directReportScopeService.directReports(email)).thenReturn(List.of());
            assertEquals(List.of(), service.listTeamGrowthSummaries(email), code);
        }
    }

    @Test
    void everyoneElse_isLimitedToTheirDirectReports() {
        for (String code : List.of("EMPLOYEE", "MANAGER", "CUSTOM_OTHER")) {
            String email = actor(code);
            lenient().when(directReportScopeService.isDirectReport(email, stranger)).thenReturn(false);
            assertThrows(AccessDeniedException.class, () -> service.getEmployeeGrowth(email, stranger), code);
        }
    }
}
