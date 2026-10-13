package com.nforce.onehr.security;

import com.nforce.onehr.entity.Permission;
import com.nforce.onehr.entity.PermissionModule;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.RolePermission;
import com.nforce.onehr.entity.SuperAdminModuleFence;
import com.nforce.onehr.repository.RolePermissionRepository;
import com.nforce.onehr.repository.SuperAdminModuleFenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito test (no Spring context, no DB — this repo's test profile doesn't seed
 * role_permissions, see RolePermissionRepository). Focused on the cascading-scope logic that
 * can't be exercised by a live single-role check alone: a TEAM grant must also authorize OWN,
 * a DEPARTMENT/LOCATION grant must authorize TEAM+OWN but not each other, and a user holding
 * multiple roles gets the union of each grant's own coverage (not a single "winning" scope).
 */
@ExtendWith(MockitoExtension.class)
class EffectivePermissionServiceTest {

    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private SuperAdminModuleFenceRepository fenceRepository;
    @InjectMocks private EffectivePermissionService effectivePermissionService;

    private static RolePermission grant(Role role, String moduleCode, String action, String scope) {
        PermissionModule module = PermissionModule.builder().id(1).code(moduleCode).displayName(moduleCode).build();
        Permission permission = Permission.builder().id(1).module(module).action(action).build();
        return RolePermission.builder().role(role).permission(permission).scope(scope).build();
    }

    private static SuperAdminModuleFence fence(String moduleCode, String action) {
        PermissionModule module = PermissionModule.builder().id(1).code(moduleCode).displayName(moduleCode).build();
        return SuperAdminModuleFence.builder().module(module).action(action).build();
    }

    @Test
    void computeAuthorities_teamGrant_cascadesToOwnAndTeam() {
        Role manager = Role.builder().id(2).code("MANAGER").build();
        when(rolePermissionRepository.findByRoleIdsWithPermissionAndModule(List.of(2)))
                .thenReturn(List.of(grant(manager, "attendance", "VIEW", "TEAM")));

        List<String> authorities = effectivePermissionService.computeAuthorities(List.of(manager))
                .stream().map(a -> a.getAuthority()).toList();

        assertTrue(authorities.contains("attendance:VIEW:OWN"));
        assertTrue(authorities.contains("attendance:VIEW:TEAM"));
        assertFalse(authorities.contains("attendance:VIEW:DEPARTMENT"));
        assertFalse(authorities.contains("attendance:VIEW:ALL"));
    }

    @Test
    void computeAuthorities_departmentAndLocationGrants_doNotCascadeIntoEachOther() {
        Role hrAdmin = Role.builder().id(3).code("HR_ADMIN").build();
        when(rolePermissionRepository.findByRoleIdsWithPermissionAndModule(List.of(3)))
                .thenReturn(List.of(grant(hrAdmin, "employees", "VIEW", "DEPARTMENT")));

        List<String> authorities = effectivePermissionService.computeAuthorities(List.of(hrAdmin))
                .stream().map(a -> a.getAuthority()).toList();

        assertTrue(authorities.contains("employees:VIEW:OWN"));
        assertTrue(authorities.contains("employees:VIEW:TEAM"));
        assertTrue(authorities.contains("employees:VIEW:DEPARTMENT"));
        assertFalse(authorities.contains("employees:VIEW:LOCATION"));
        assertFalse(authorities.contains("employees:VIEW:ALL"));
    }

    @Test
    void computeAuthorities_multipleRoles_unionsEachGrantsOwnCoverage() {
        Role manager = Role.builder().id(2).code("MANAGER").build();
        Role hrAdmin = Role.builder().id(3).code("HR_ADMIN").build();
        when(rolePermissionRepository.findByRoleIdsWithPermissionAndModule(List.of(2, 3)))
                .thenReturn(List.of(
                        grant(manager, "leave", "APPROVE", "TEAM"),
                        grant(hrAdmin, "leave", "APPROVE", "ALL")));

        List<String> authorities = effectivePermissionService.computeAuthorities(List.of(manager, hrAdmin))
                .stream().map(a -> a.getAuthority()).toList();

        assertTrue(authorities.contains("leave:APPROVE:OWN"));
        assertTrue(authorities.contains("leave:APPROVE:TEAM"));
        assertTrue(authorities.contains("leave:APPROVE:DEPARTMENT"));
        assertTrue(authorities.contains("leave:APPROVE:LOCATION"));
        assertTrue(authorities.contains("leave:APPROVE:ALL"));
    }

    @Test
    void computeAuthorities_noRoles_returnsEmptyWithoutQueryingRepository() {
        List<?> authorities = effectivePermissionService.computeAuthorities(List.of());

        assertTrue(authorities.isEmpty());
        verifyNoInteractions(rolePermissionRepository);
    }

    @Test
    void effectivePermissions_groupsByModuleThenActionThenScope() {
        Role manager = Role.builder().id(2).code("MANAGER").build();
        when(rolePermissionRepository.findByRoleIdsWithPermissionAndModule(List.of(2)))
                .thenReturn(List.of(grant(manager, "attendance", "VIEW", "TEAM")));

        Map<String, Map<String, Set<String>>> permissions =
                effectivePermissionService.effectivePermissions(List.of(manager));

        assertEquals(Set.of("TEAM"), permissions.get("attendance").get("VIEW"));
    }

    // ── Super Admin module fencing ──────────────────────────────────────────

    @Test
    void computeAuthorities_wholeModuleFence_removesEveryActionAndScopeForSuperAdmin() {
        Role superAdmin = Role.builder().id(4).code("SUPER_ADMIN").build();
        when(rolePermissionRepository.findByRoleIdsWithPermissionAndModule(List.of(4)))
                .thenReturn(List.of(grant(superAdmin, "payroll", "VIEW", "ALL"), grant(superAdmin, "payroll", "CONFIGURE", "ALL")));
        when(fenceRepository.findAllWithModule()).thenReturn(List.of(fence("payroll", "*")));

        List<String> authorities = effectivePermissionService.computeAuthorities(List.of(superAdmin))
                .stream().map(a -> a.getAuthority()).toList();

        assertTrue(authorities.stream().noneMatch(a -> a.startsWith("payroll:")));
    }

    @Test
    void computeAuthorities_specificActionFence_leavesOtherActionsOnSameModuleIntact() {
        Role superAdmin = Role.builder().id(4).code("SUPER_ADMIN").build();
        when(rolePermissionRepository.findByRoleIdsWithPermissionAndModule(List.of(4)))
                .thenReturn(List.of(grant(superAdmin, "leave", "VIEW", "ALL"), grant(superAdmin, "leave", "CONFIGURE", "ALL")));
        when(fenceRepository.findAllWithModule()).thenReturn(List.of(fence("leave", "CONFIGURE")));

        List<String> authorities = effectivePermissionService.computeAuthorities(List.of(superAdmin))
                .stream().map(a -> a.getAuthority()).toList();

        assertTrue(authorities.contains("leave:VIEW:ALL"));
        assertFalse(authorities.contains("leave:CONFIGURE:ALL"));
    }

    @Test
    void computeAuthorities_fenceIgnoredForAccountThatDoesNotHoldSuperAdmin() {
        Role hrAdmin = Role.builder().id(3).code("HR_ADMIN").build();
        when(rolePermissionRepository.findByRoleIdsWithPermissionAndModule(List.of(3)))
                .thenReturn(List.of(grant(hrAdmin, "leave", "CONFIGURE", "ALL")));

        List<String> authorities = effectivePermissionService.computeAuthorities(List.of(hrAdmin))
                .stream().map(a -> a.getAuthority()).toList();

        assertTrue(authorities.contains("leave:CONFIGURE:ALL"));
        verifyNoInteractions(fenceRepository);
    }

    @Test
    void effectivePermissions_wholeModuleFence_removesModuleEntirelyForSuperAdmin() {
        Role superAdmin = Role.builder().id(4).code("SUPER_ADMIN").build();
        when(rolePermissionRepository.findByRoleIdsWithPermissionAndModule(List.of(4)))
                .thenReturn(List.of(grant(superAdmin, "payroll", "VIEW", "ALL")));
        when(fenceRepository.findAllWithModule()).thenReturn(List.of(fence("payroll", "*")));

        Map<String, Map<String, Set<String>>> permissions =
                effectivePermissionService.effectivePermissions(List.of(superAdmin));

        assertFalse(permissions.containsKey("payroll"));
    }
}
