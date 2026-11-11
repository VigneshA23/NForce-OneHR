package com.nforce.onehr.service;

import com.nforce.onehr.dto.EmployeeResponse;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.security.SeedMatrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Who can be selected as a reporting manager is an explicit organisational responsibility
 * (employees:VIEW:TEAM), not "any role" and not a hardcoded role-code list: a custom role that only
 * administers Roles & Permissions must never qualify, while a custom team-lead role granted the
 * permission does — with no built-in MANAGER role involved.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeServicePotentialManagersTest {

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private com.nforce.onehr.repository.EmployeeManagerHistoryRepository historyRepository;
    @Spy private EffectivePermissionService effectivePermissionService = SeedMatrix.permissionService(Map.of(
            "CUSTOM_TEAM_LEAD", Map.of("employees:VIEW", "TEAM"),
            "CUSTOM_ROLES_ONLY", Map.of("roles:VIEW", "ALL", "user_roles:VIEW", "ALL")));

    @InjectMocks private EmployeeService employeeService;

    private static User user(String email, Role... roles) {
        return User.builder().id(UUID.randomUUID()).email(email).roles(new HashSet<>(List.of(roles))).build();
    }

    private static Role custom(String code) {
        return Role.builder().id(100 + Math.abs(code.hashCode() % 100)).code(code).displayName(code).system(false).active(true).build();
    }

    @Test
    void onlyUsersWithTeamResponsibilityAreEligible_customTeamLeadYes_rolesOnlyNo() {
        Role employee = SeedMatrix.role("EMPLOYEE");
        User manager = user("manager@t", employee, SeedMatrix.role("MANAGER"));
        User hr = user("hr@t", employee, SeedMatrix.role("HR_ADMIN"));
        User sa = user("sa@t", employee, SeedMatrix.role("SUPER_ADMIN"));
        User teamLead = user("lead@t", employee, custom("CUSTOM_TEAM_LEAD"));
        User rolesOnly = user("rolesonly@t", employee, custom("CUSTOM_ROLES_ONLY"));
        User plain = user("plain@t", employee);
        User deleted = user("gone@t", employee, SeedMatrix.role("MANAGER"));
        deleted.setDeletedAt(Instant.now());
        List<User> all = List.of(manager, hr, sa, teamLead, rolesOnly, plain, deleted);

        when(userRepository.findAllWithRoles()).thenReturn(all);
        when(employeeRepository.findNamesByUserIds(org.mockito.ArgumentMatchers.<Set<UUID>>any()))
                .thenAnswer(inv -> inv.<Set<UUID>>getArgument(0).stream()
                        .map(id -> new Object[]{id, "Name " + id}).collect(Collectors.toList()));

        List<EmployeeResponse> result = employeeService.listPotentialManagers();
        Map<UUID, String> tierById = result.stream().collect(Collectors.toMap(EmployeeResponse::getUserId, EmployeeResponse::getRole));

        assertEquals(Set.of(manager.getId(), hr.getId(), sa.getId(), teamLead.getId()), tierById.keySet());
        assertFalse(tierById.containsKey(rolesOnly.getId()), "a Roles-only custom role must not qualify");
        assertFalse(tierById.containsKey(plain.getId()));
        assertFalse(tierById.containsKey(deleted.getId()));
        // Hierarchy tier: built-in admin roles as-is, a custom-role team lead sits at the MANAGER tier.
        assertEquals("MANAGER", tierById.get(manager.getId()));
        assertEquals("HR_ADMIN", tierById.get(hr.getId()));
        assertEquals("SUPER_ADMIN", tierById.get(sa.getId()));
        assertEquals("MANAGER", tierById.get(teamLead.getId()));
        // The picker can warn when a candidate couldn't actually approve their reports' requests:
        // a Manager can (leave:APPROVE:TEAM); a custom team lead with only team visibility cannot.
        Map<UUID, Boolean> canApprove = result.stream().collect(Collectors.toMap(EmployeeResponse::getUserId, EmployeeResponse::getCanApproveRequests));
        assertTrue(canApprove.get(manager.getId()));
        assertTrue(canApprove.get(hr.getId()));
        assertFalse(canApprove.get(teamLead.getId()));
        assertTrue(effectivePermissionService.isEligibleReportingManager(teamLead));
        assertFalse(effectivePermissionService.isEligibleReportingManager(rolesOnly));
    }

    // ── Employee Master vs role categories ────────────────────────────────────

    private static com.nforce.onehr.entity.Employee emp(User u, String name) {
        return com.nforce.onehr.entity.Employee.builder().userId(u.getId()).user(u).employeeCode("E-" + name).fullName(name).build();
    }

    @Test
    void employeeMaster_listsEveryEmployeeIncludingAdministrators_whilePlainCategoriesStaySeparate() {
        Role employeeRow = SeedMatrix.role("EMPLOYEE");
        User plain = user("plain@t");                                        // no role row at all
        User legacyPlain = user("legacy@t", employeeRow);                    // legacy EMPLOYEE row only
        User admin = user("admin@t", SeedMatrix.role("SUPER_ADMIN"));        // no EMPLOYEE row
        User customOnly = user("custom@t", custom("CUSTOM_ROLES_ONLY"));
        when(employeeRepository.findAllWithDetails()).thenReturn(List.of(
                emp(plain, "Plain"), emp(legacyPlain, "Legacy"), emp(admin, "Admin"), emp(customOnly, "Custom")));

        List<EmployeeResponse> master = employeeService.listEmployees();
        Map<String, List<String>> categoriesByEmail = master.stream().collect(Collectors.toMap(EmployeeResponse::getEmail,
                r -> r.getCategories().stream().map(c -> c.code()).toList()));

        // The Employee Master has everyone — administrators are staff too…
        assertEquals(4, master.size());
        // …while the role categories keep admins/custom-role holders out of the default Employee category.
        assertEquals(List.of("EMPLOYEE"), categoriesByEmail.get("plain@t"));
        assertEquals(List.of("EMPLOYEE"), categoriesByEmail.get("legacy@t"));
        assertEquals(List.of("SUPER_ADMIN"), categoriesByEmail.get("admin@t"));
        assertEquals(List.of("CUSTOM_ROLES_ONLY"), categoriesByEmail.get("custom@t"));
        // A custom-only user's primary role stays within the built-in contract (never CUSTOM_*).
        assertEquals("EMPLOYEE", master.stream().filter(r -> r.getEmail().equals("custom@t")).findFirst().orElseThrow().getRole());
    }
}
