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
            "CUSTOM_OTHER", Map.of("talent_skills:CONFIGURE", "ALL")));

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
    void everyoneElse_isLimitedToTheirDirectReports() {
        for (String code : List.of("EMPLOYEE", "MANAGER", "CUSTOM_OTHER")) {
            String email = actor(code);
            when(directReportScopeService.isDirectReport(email, stranger)).thenReturn(false);
            assertThrows(AccessDeniedException.class, () -> service.getEmployeeGrowth(email, stranger), code);
        }
    }
}
