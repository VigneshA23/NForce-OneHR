package com.nforce.onehr.service;

import com.nforce.onehr.dto.roles.RoleDtos.CreateRoleRequest;
import com.nforce.onehr.dto.roles.RoleDtos.Grant;
import com.nforce.onehr.dto.roles.RoleDtos.UpdateRoleRequest;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.RolePermission;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.*;
import com.nforce.onehr.security.EffectivePermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Roles & Permissions administration rules, run against the REAL EffectivePermissionService over
 * {@link PermissionFixture}: system-role protection, the Super Admin lock, registered-permission
 * and configurable-module validation, and the privilege ceiling on permission edits.
 */
@ExtendWith(MockitoExtension.class)
class RoleAdminServiceTest {

    @Mock private RoleRepository roleRepository;
    @Mock private PermissionRepository permissionRepository;
    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserManagementService userManagementService;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    @InjectMocks private RoleAdminService service;

    private final PermissionFixture f = new PermissionFixture();
    private final Role custom = Role.builder().id(20).code("CUSTOM_PAYROLL").displayName("Payroll").build();

    @BeforeEach
    void setUp() {
        f.bind(rolePermissionRepository);
        ReflectionTestUtils.setField(service, "effectivePermissionService", new EffectivePermissionService(rolePermissionRepository));
        lenient().when(permissionRepository.findAllWithModule()).thenReturn(new ArrayList<>(f.permissions.values()));
        for (Role r : List.of(f.employee, f.manager, f.hrAdmin, f.superAdmin, f.delivery, f.userAdmin, custom)) {
            lenient().when(roleRepository.findById(r.getId())).thenReturn(Optional.of(r));
        }
        lenient().when(roleRepository.save(any(Role.class))).thenAnswer(inv -> {
            Role r = inv.getArgument(0);
            if (r.getId() == null) r.setId(30);
            lenient().when(roleRepository.findById(r.getId())).thenReturn(Optional.of(r));
            return r;
        });
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        actor("sa@test.com", f.superAdmin, f.employee);
        actor("ua@test.com", f.userAdmin, f.employee);
    }

    private void actor(String email, Role... roles) {
        User u = User.builder().id(UUID.randomUUID()).email(email).roles(new HashSet<>(Set.of(roles))).build();
        lenient().when(userRepository.findByEmail(email)).thenReturn(Optional.of(u));
    }

    @SuppressWarnings("unchecked")
    private List<RolePermission> captureSaved() {
        ArgumentCaptor<List<RolePermission>> c = ArgumentCaptor.forClass(List.class);
        verify(rolePermissionRepository).saveAll(c.capture());
        return c.getValue();
    }

    // ─── Create / update / delete ──────────────────────────────────────────────

    @Test
    void create_customRole_getsStablePrefixedCode_andIsAudited() {
        service.create(new CreateRoleRequest("  Payroll Viewer ", "Reads payroll", null), "sa@test.com");

        ArgumentCaptor<Role> c = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository).save(c.capture());
        assertEquals("CUSTOM_PAYROLL_VIEWER", c.getValue().getCode());
        assertEquals("Payroll Viewer", c.getValue().getDisplayName());
        assertFalse(c.getValue().isSystem());
        verify(auditService).log(any(), eq("ROLE_CREATED"), isNull(), isNull(), anyString());
    }

    @Test
    void create_codeCollision_getsNumericSuffix() {
        when(roleRepository.existsByCode("CUSTOM_PAYROLL_VIEWER")).thenReturn(true);

        service.create(new CreateRoleRequest("Payroll Viewer", null, null), "sa@test.com");

        ArgumentCaptor<Role> c = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository).save(c.capture());
        assertEquals("CUSTOM_PAYROLL_VIEWER_2", c.getValue().getCode());
    }

    @Test
    void create_duplicateName_isRejectedCaseInsensitively() {
        when(roleRepository.existsByDisplayNameIgnoreCase("super admin")).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.create(new CreateRoleRequest("super admin", null, null), "sa@test.com"));
        verify(roleRepository, never()).save(any());
    }

    @Test
    void create_withGrants_requiresConfigurePermission() {
        actor("noconf@test.com", f.hrAdmin);
        List<Grant> grants = List.of(new Grant("employees", "EDIT", "ALL"));

        assertThrows(AccessDeniedException.class,
                () -> service.create(new CreateRoleRequest("X", null, grants), "noconf@test.com"));
    }

    @Test
    void systemRole_cannotBeRenamedDeactivatedOrDeleted() {
        assertThrows(IllegalArgumentException.class,
                () -> service.update(f.hrAdmin.getId(), new UpdateRoleRequest("People Ops", null, 0), "sa@test.com"));
        assertThrows(IllegalArgumentException.class, () -> service.setActive(f.manager.getId(), false, 0, "sa@test.com"));
        assertThrows(IllegalArgumentException.class, () -> service.delete(f.employee.getId(), false, "sa@test.com"));
        verify(roleRepository, never()).delete(any());
    }

    @Test
    void placeholderSystemRoles_areHiddenFromTheAdminApi() {
        assertThrows(NoSuchElementException.class, () -> service.get(f.delivery.getId()));
    }

    @Test
    void delete_roleStillAssigned_isRejected() {
        when(userRepository.findHoldersOfRole(custom.getId())).thenReturn(List.of(User.builder().id(UUID.randomUUID()).build()));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.delete(custom.getId(), false, "sa@test.com"));
        assertTrue(e.getMessage().contains("assigned to 1 user"));
        verify(roleRepository, never()).delete(any());
    }

    @Test
    void delete_unassignedCustomRole_removesItsGrantsThenTheRole() {
        service.delete(custom.getId(), false, "sa@test.com");

        var order = inOrder(rolePermissionRepository, roleRepository);
        order.verify(rolePermissionRepository).deleteByRole_Id(custom.getId());
        order.verify(roleRepository).deleteAssignments(custom.getId());
        order.verify(roleRepository).delete(custom);
        verify(auditService).log(any(), eq("ROLE_DELETED"), isNull(), anyString(), isNull());
    }

    @Test
    void staleVersion_isRejected_soAConcurrentEditIsNotOverwritten() {
        custom.setVersion(3);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.setPermissions(custom.getId(), List.of(new Grant("employees", "VIEW", "ALL")), 2, "sa@test.com"));
        assertTrue(e.getMessage().contains("changed by someone else"));
        assertThrows(IllegalStateException.class,
                () -> service.update(custom.getId(), new UpdateRoleRequest("Renamed", null, 2), "sa@test.com"));
        assertThrows(IllegalStateException.class, () -> service.setActive(custom.getId(), false, 2, "sa@test.com"));
        verify(rolePermissionRepository, never()).saveAll(any());
        verify(roleRepository, never()).save(any());
    }

    @Test
    void delete_withRemoveFromUsers_takesTheRoleOffEachHolderThenDeletes() {
        User holder = User.builder().id(UUID.randomUUID()).roles(new HashSet<>(Set.of(f.employee, f.manager, custom))).build();
        when(userRepository.findHoldersOfRole(custom.getId())).thenReturn(List.of(holder));

        service.delete(custom.getId(), true, "sa@test.com");

        var order = inOrder(userManagementService, roleRepository);
        // Same path as Manage Roles: guards, sign-out and ROLE_CHANGED audit per holder.
        order.verify(userManagementService).setUserRoles(eq(holder.getId()),
                argThat(ids -> ids.containsAll(List.of(f.employee.getId(), f.manager.getId())) && !ids.contains(custom.getId())),
                eq("sa@test.com"));
        order.verify(roleRepository).delete(custom);
    }

    @Test
    void delete_withRemoveFromUsers_refusesWhenTheActorHoldsTheRole() {
        User saHoldingIt = User.builder().id(UUID.randomUUID()).email("sa2@test.com")
                .roles(new HashSet<>(Set.of(f.superAdmin, custom))).build();
        when(userRepository.findByEmail("sa2@test.com")).thenReturn(Optional.of(saHoldingIt));
        when(userRepository.findHoldersOfRole(custom.getId())).thenReturn(List.of(saHoldingIt));

        assertThrows(IllegalArgumentException.class, () -> service.delete(custom.getId(), true, "sa2@test.com"));
        verifyNoInteractions(userManagementService);
        verify(roleRepository, never()).delete(any());
    }

    // ─── Permissions ───────────────────────────────────────────────────────────

    @Test
    void superAdminPermissions_areLocked() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setPermissions(f.superAdmin.getId(), List.of(), 0, "sa@test.com"));
        verify(rolePermissionRepository, never()).deleteAll(any());
    }

    @Test
    void unregisteredPermission_isRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.setPermissions(custom.getId(), List.of(new Grant("payroll", "VIEW", "ALL")), 0, "sa@test.com"));
        assertTrue(e.getMessage().contains("Unknown permission"));
    }

    @Test
    void invalidScope_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setPermissions(custom.getId(), List.of(new Grant("employees", "VIEW", "EVERYONE")), 0, "sa@test.com"));
    }

    @Test
    void duplicatePermission_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.setPermissions(custom.getId(),
                List.of(new Grant("employees", "VIEW", "ALL"), new Grant("employees", "VIEW", "TEAM")), 0, "sa@test.com"));
    }

    @Test
    void notYetConfigurableModule_isRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.setPermissions(custom.getId(), List.of(new Grant("leave", "APPROVE", "ALL")), 0, "sa@test.com"));
        assertTrue(e.getMessage().contains("can't be configured yet"));
    }

    @Test
    void cannotGrantAPermissionTheActorDoesNotHold() {
        // ua holds roles:CONFIGURE but not employees:EDIT — granting it would be escalation.
        assertThrows(IllegalArgumentException.class,
                () -> service.setPermissions(custom.getId(), List.of(new Grant("employees", "EDIT", "ALL")), 0, "ua@test.com"));
        verify(rolePermissionRepository, never()).saveAll(any());
    }

    @Test
    void cannotRemoveAPermissionTheActorDoesNotHold() {
        // employees:EDIT:ALL is configurable but beyond ua — removing it is just as privileged.
        f.grant(custom, "employees", "EDIT", "ALL");
        assertThrows(IllegalArgumentException.class,
                () -> service.setPermissions(custom.getId(), List.of(), 0, "ua@test.com"));
        verify(rolePermissionRepository, never()).deleteAll(any());
    }

    @Test
    void onlySuperAdmin_canEditSystemRolePermissions_evenWithinCeiling() {
        // ua holds roles:CONFIGURE and user_management:VIEW:ALL, so the grant itself is within its
        // ceiling — the system-role rule must still refuse it.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.setPermissions(
                f.employee.getId(), List.of(new Grant("user_management", "VIEW", "ALL")), 0, "ua@test.com"));
        assertTrue(e.getMessage().contains("Only a Super Admin"));
        verify(rolePermissionRepository, never()).saveAll(any());
    }

    @Test
    void canGrantWithinOwnCeiling_atANarrowerScope() {
        service.setPermissions(custom.getId(), List.of(new Grant("user_management", "VIEW", "TEAM")), 0, "ua@test.com");

        List<RolePermission> saved = captureSaved();
        assertEquals(1, saved.size());
        assertEquals("TEAM", saved.get(0).getScope());
    }

    @Test
    void setPermissions_diffs_updatesScopeInPlace_deletesRemoved_leavesNonConfigurableRowsAlone() {
        RolePermission keptButRescoped = f.grant(custom, "employees", "VIEW", "TEAM");
        RolePermission removed = f.grant(custom, "employees", "EDIT", "ALL");
        RolePermission nonConfigurable = f.grant(custom, "leave", "VIEW", "ALL");

        service.setPermissions(custom.getId(), List.of(
                new Grant("employees", "VIEW", "ALL"),
                new Grant("user_roles", "VIEW", "ALL")), 0, "sa@test.com");

        List<RolePermission> saved = captureSaved();
        assertTrue(saved.contains(keptButRescoped));
        assertEquals("ALL", keptButRescoped.getScope());
        assertTrue(saved.stream().anyMatch(rp -> rp.getId() == null && rp.getPermission().getModule().getCode().equals("user_roles")));
        verify(rolePermissionRepository).deleteAll(List.of(removed));
        assertFalse(saved.contains(nonConfigurable));
        assertEquals("ALL", nonConfigurable.getScope());
    }

    @Test
    void editingAnotherSystemRole_isAllowedWithinCeiling() {
        service.setPermissions(f.manager.getId(), List.of(new Grant("employees", "VIEW", "TEAM")), 0, "sa@test.com");

        assertEquals(1, captureSaved().size());
        // Manager's leave:APPROVE:TEAM lives in a non-configurable module and is untouched.
        verify(rolePermissionRepository).deleteAll(List.of());
    }

    // ─── Membership delegates to the shared assignment guard ───────────────────

    @Test
    void removeMember_employeeRole_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.removeMember(f.employee.getId(), UUID.randomUUID(), "sa@test.com"));
        verifyNoInteractions(userManagementService);
    }

    @Test
    void addMember_goesThroughSetUserRoles_withTheUnionOfRoles() {
        User target = User.builder().id(UUID.randomUUID()).roles(new HashSet<>(Set.of(f.employee))).build();
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));

        service.addMember(custom.getId(), target.getId(), "sa@test.com");

        verify(userManagementService).setUserRoles(target.getId(), new LinkedHashSet<>(List.of(f.employee.getId(), custom.getId())), "sa@test.com");
    }
}
