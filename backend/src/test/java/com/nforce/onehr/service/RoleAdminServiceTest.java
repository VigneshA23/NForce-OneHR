package com.nforce.onehr.service;

import com.nforce.onehr.dto.roles.RoleDtos.CatalogueAction;
import com.nforce.onehr.dto.roles.RoleDtos.CatalogueNode;
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
import org.mockito.Spy;
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
    @Mock private com.nforce.onehr.security.ForceLogoutBroadcaster forceLogoutBroadcaster;
    @Mock private AuditLogRepository auditLogRepository;
    @Spy private com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    // The real catalogue file: scope validation must be exercised against what production loads.
    @Spy private com.nforce.onehr.security.PermissionCatalogue permissionCatalogue = com.nforce.onehr.security.PermissionCatalogue.loaded();

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
    void grantsOutsideTheCatalogueScopeRange_areRefused_evenWhenTheActorHoldsThePermission() {
        // DEPARTMENT / LOCATION are never evaluated, and user_management is organisation-wide only:
        // a grant there would silently do nothing, so it is refused up front.
        for (Grant g : List.of(new Grant("employees", "VIEW", "DEPARTMENT"), new Grant("employees", "VIEW", "LOCATION"),
                new Grant("user_management", "VIEW", "TEAM"), new Grant("user_management", "VIEW", "OWN"))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service.setPermissions(custom.getId(), List.of(g), 0, "ua@test.com"), g.toString());
            assertTrue(e.getMessage().contains("not supported") || e.getMessage().contains("Invalid scope"), e.getMessage());
        }
        verify(rolePermissionRepository, never()).saveAll(any());
    }

    @Test
    void catalogueTree_isModuleSubmoduleSection_withScopeRanges_andReadOnlyForUnconfigurableNodes() {
        List<CatalogueNode> tree = service.catalogueTree();

        CatalogueNode attendance = tree.stream().filter(n -> n.code().equals("mod.attendance")).findFirst().orElseThrow();
        assertEquals("MODULE", attendance.type());
        assertTrue(attendance.actions().isEmpty(), "a module holds no grants");
        CatalogueNode requests = attendance.children().stream().filter(n -> n.code().equals("sub.attendance_requests")).findFirst().orElseThrow();
        assertEquals("SUBMODULE", requests.type());
        CatalogueNode regularization = requests.children().stream().filter(n -> n.code().equals("regularization")).findFirst().orElseThrow();
        assertEquals("SECTION", regularization.type());
        // The fixture registers no regularization permission, so the node shows read-only with no actions
        // rather than offering grants the database would not accept.
        assertTrue(regularization.actions().isEmpty());
        assertFalse(regularization.configurable());

        CatalogueNode leave = tree.stream().filter(n -> n.code().equals("mod.leave")).findFirst().orElseThrow()
                .children().stream().filter(n -> n.code().equals("leave")).findFirst().orElseThrow();
        assertEquals("SUBMODULE", leave.type());
        assertEquals(new CatalogueAction("APPROVE", "TEAM", "ALL"),
                leave.actions().stream().filter(a -> a.action().equals("APPROVE")).findFirst().orElseThrow());
    }

    @Test
    void changesOnAHighSensitivityNode_areFlaggedInTheAuditEntry_ordinaryOnesAreNot() {
        // A realistic repository: saved grants become visible to the next read (so the diff is real).
        when(rolePermissionRepository.saveAll(any())).thenAnswer(inv -> {
            List<RolePermission> saved = inv.getArgument(0);
            saved.stream().filter(rp -> rp.getId() == null).forEach(f.rows::add);
            return saved;
        });
        service.setPermissions(custom.getId(), List.of(new Grant("user_management", "VIEW", "ALL")), 0, "ua@test.com");
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> snapshots =
                (org.mockito.ArgumentCaptor<Map<String, Object>>) (org.mockito.ArgumentCaptor<?>) org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(auditSnapshot, atLeastOnce()).toJson(snapshots.capture());
        assertTrue(snapshots.getAllValues().stream().anyMatch(m -> m.get("highImpact") instanceof List<?> l
                        && l.contains("user_management:VIEW:ALL")),
                "user_management is HIGH sensitivity: the change must be flagged");

        clearInvocations(auditSnapshot);
        service.setPermissions(custom.getId(), List.of(new Grant("employees", "VIEW", "TEAM")), 0, "ua@test.com");
        verify(auditSnapshot, atLeastOnce()).toJson(snapshots.capture());
        assertTrue(snapshots.getAllValues().stream().noneMatch(m -> m.get("highImpact") instanceof List<?> l
                && l.contains("employees:VIEW:TEAM")));
    }

    @Test
    void canGrantWithinOwnCeiling_atANarrowerScope() {
        service.setPermissions(custom.getId(), List.of(new Grant("employees", "VIEW", "TEAM")), 0, "ua@test.com");

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
    void permissionChange_pushesPermissionsChangedToEveryHolder_noTransactionHere_soImmediately() {
        User holder1 = User.builder().id(UUID.randomUUID()).email("h1@test.com").roles(new HashSet<>(Set.of(custom))).build();
        User holder2 = User.builder().id(UUID.randomUUID()).email("h2@test.com").roles(new HashSet<>(Set.of(custom))).build();
        when(userRepository.findHoldersOfRole(custom.getId())).thenReturn(List.of(holder1, holder2));
        // A realistic repository: saved grants become visible to the next read (so the diff is real).
        when(rolePermissionRepository.saveAll(any())).thenAnswer(inv -> {
            List<RolePermission> saved = inv.getArgument(0);
            saved.stream().filter(rp -> rp.getId() == null).forEach(f.rows::add);
            return saved;
        });

        service.setPermissions(custom.getId(), List.of(new Grant("employees", "VIEW", "TEAM")), 0, "ua@test.com");

        verify(forceLogoutBroadcaster).permissionsChanged(List.of(holder1.getId(), holder2.getId()));
    }

    @Test
    void unchangedPermissions_pushNothing() {
        service.setPermissions(custom.getId(), List.of(), 0, "ua@test.com");

        verifyNoInteractions(forceLogoutBroadcaster);
    }

    @Test
    void deactivatingARole_pushesToItsHolders_soTheirNavDropsItsAccessAtOnce() {
        User holder = User.builder().id(UUID.randomUUID()).email("h@test.com").roles(new HashSet<>(Set.of(custom))).build();
        when(userRepository.findHoldersOfRole(custom.getId())).thenReturn(List.of(holder));

        service.setActive(custom.getId(), false, 0, "sa@test.com");

        verify(forceLogoutBroadcaster).permissionsChanged(List.of(holder.getId()));
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
    void employeeCategory_cannotHoldPermissions_evenForASuperAdmin_andIsReportedAsNotEditable() {
        assertThrows(IllegalArgumentException.class, () -> service.setPermissions(
                f.employee.getId(), List.of(new Grant("user_management", "VIEW", "ALL")), 0, "sa@test.com"));
        verify(rolePermissionRepository, never()).saveAll(any());

        when(roleRepository.findAll()).thenReturn(List.of(f.employee));
        var employeeRow = service.list().get(0);
        assertFalse(employeeRow.permissionsEditable());
        assertFalse(employeeRow.metadataEditable());
    }

    @Test
    void addMember_employeeCategory_isRejected_itIsADefaultCategoryNotARole() {
        assertThrows(IllegalArgumentException.class,
                () -> service.addMember(f.employee.getId(), UUID.randomUUID(), "sa@test.com"));
        verifyNoInteractions(userManagementService);
    }

    @Test
    void employeeCategory_membersAreOnlyPeopleWithNoOtherRole_neverAdminsOrCustomRoleHolders() {
        User plain = User.builder().id(UUID.randomUUID()).email("plain@test.com").active(true).build();
        when(userRepository.findUsersWithoutAssignedRole()).thenReturn(List.of(plain));

        var detail = service.get(f.employee.getId());

        assertEquals(List.of(plain.getId()), detail.members().stream().map(m -> m.userId()).toList());
        // Membership of the Employee category is derived, never the raw user_roles holders.
        verify(userRepository, never()).findHoldersOfRole(f.employee.getId());
    }

    @Test
    void list_employeeCategoryCount_isThePeopleWithNoOtherRole_notEveryHolderOfTheRow() {
        when(roleRepository.findAll()).thenReturn(List.of(f.employee, f.manager));
        when(userRepository.countUsersWithoutAssignedRole()).thenReturn(7L);
        when(userRepository.countHoldersByRole()).thenReturn(List.<Object[]>of(
                new Object[]{f.employee.getId(), 999L}, new Object[]{f.manager.getId(), 3L}));

        var summaries = service.list();

        assertEquals(7L, summaries.stream().filter(r -> "EMPLOYEE".equals(r.code())).findFirst().orElseThrow().userCount());
        assertEquals(3L, summaries.stream().filter(r -> "MANAGER".equals(r.code())).findFirst().orElseThrow().userCount());
    }

    @Test
    void addMember_goesThroughSetUserRoles_withTheUnionOfRoles() {
        User target = User.builder().id(UUID.randomUUID()).roles(new HashSet<>(Set.of(f.employee))).build();
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));

        service.addMember(custom.getId(), target.getId(), "sa@test.com");

        verify(userManagementService).setUserRoles(target.getId(), new LinkedHashSet<>(List.of(f.employee.getId(), custom.getId())), "sa@test.com");
    }

    // ─── History, reverse lookup, bulk membership ──────────────────────────────

    private com.nforce.onehr.entity.AuditLog audit(long id, String action, UUID actor, String before, String after) {
        return com.nforce.onehr.entity.AuditLog.builder().id(id).action(action).actorId(actor)
                .beforeState(before).afterState(after).occurredAt(java.time.Instant.parse("2026-11-10T10:00:00Z")).build();
    }

    @Test
    void history_expandsPermissionEdits_intoAddedAndRemoved_andOnlyForThisRole() {
        UUID actor = UUID.randomUUID();
        when(employeeRepository.findNamesByUserIds(any())).thenReturn(List.<Object[]>of(new Object[]{actor, "Asha Rao"}));
        when(auditLogRepository.findByActionInOrderByOccurredAtDesc(any(), any())).thenReturn(List.of(
                audit(3, "ROLE_PERMISSIONS_UPDATED", actor,
                        "{\"role\":\"CUSTOM_PAYROLL\",\"permissions\":[\"employees:VIEW:ALL\",\"leave:VIEW:ALL\"]}",
                        "{\"role\":\"CUSTOM_PAYROLL\",\"permissions\":[\"employees:VIEW:ALL\",\"roles:VIEW:ALL\"]}"),
                audit(2, "ROLE_PERMISSIONS_UPDATED", actor, "{\"role\":\"CUSTOM_OTHER\",\"permissions\":[]}",
                        "{\"role\":\"CUSTOM_OTHER\",\"permissions\":[\"leave:VIEW:ALL\"]}"),
                audit(1, "ROLE_UPDATED", actor, "{\"name\":\"Old\",\"description\":null}",
                        "{\"role\":\"CUSTOM_PAYROLL\",\"name\":\"Payroll\",\"description\":null}")));

        var history = service.history(custom.getId());

        assertEquals(2, history.size(), "another role's change must not leak in");
        assertEquals(List.of("roles:VIEW:ALL"), history.get(0).added());
        assertEquals(List.of("leave:VIEW:ALL"), history.get(0).removed());
        assertEquals("Asha Rao", history.get(0).actorName());
        assertTrue(history.get(1).summary().contains("renamed"));
    }

    @Test
    void holders_listsUsersThroughActiveRoles_atTheirWidestScope_withTheRolesThatConferIt() {
        User both = User.builder().id(UUID.randomUUID()).email("both@test.com").active(true).build();
        User onlyTeam = User.builder().id(UUID.randomUUID()).email("team@test.com").active(true).build();
        RolePermission all = f.grant(f.hrAdmin, "employees", "VIEW", "ALL");
        RolePermission team = f.grant(f.manager, "employees", "VIEW", "TEAM");
        when(rolePermissionRepository.findActiveGrants("employees", "VIEW")).thenReturn(List.of(all, team));
        when(userRepository.findHoldersOfRole(f.hrAdmin.getId())).thenReturn(List.of(both));
        when(userRepository.findHoldersOfRole(f.manager.getId())).thenReturn(List.of(both, onlyTeam));
        when(employeeRepository.findNamesByUserIds(any())).thenReturn(List.<Object[]>of(
                new Object[]{both.getId(), "Both"}, new Object[]{onlyTeam.getId(), "Team"}));

        var holders = service.holders("employees", "VIEW");

        assertEquals(2, holders.size());
        var b = holders.stream().filter(h -> h.userId().equals(both.getId())).findFirst().orElseThrow();
        assertEquals("ALL", b.scope());
        assertEquals(List.of("HR Admin", "Manager"), b.roles());
        assertEquals("TEAM", holders.stream().filter(h -> h.userId().equals(onlyTeam.getId())).findFirst().orElseThrow().scope());
    }

    @Test
    void holders_ofAnUnregisteredPermission_isNotFound() {
        assertThrows(NoSuchElementException.class, () -> service.holders("employees", "TELEPORT"));
    }

    @Test
    void bulkMembers_reportsEachUserSeparately_oneRefusalNeverBlocksTheOthers() {
        User ok = User.builder().id(UUID.randomUUID()).roles(new HashSet<>(Set.of(f.employee))).build();
        User refused = User.builder().id(UUID.randomUUID()).roles(new HashSet<>(Set.of(f.employee))).build();
        User already = User.builder().id(UUID.randomUUID()).roles(new HashSet<>(Set.of(custom))).build();
        for (User u : List.of(ok, refused, already)) when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        when(employeeRepository.findNamesByUserIds(any())).thenReturn(List.<Object[]>of(
                new Object[]{ok.getId(), "Ok"}, new Object[]{refused.getId(), "Refused"}, new Object[]{already.getId(), "Already"}));
        lenient().doThrow(new IllegalArgumentException("You can only assign or remove roles whose permissions you hold yourself."))
                .when(userManagementService).setUserRoles(eq(refused.getId()), any(), eq("ua@test.com"));

        var results = service.bulkMembers(custom.getId(), List.of(ok.getId(), refused.getId(), already.getId(), ok.getId()), false, "ua@test.com");

        assertEquals(3, results.size(), "duplicates collapsed");
        assertTrue(results.get(0).ok());
        assertEquals("Added", results.get(0).message());
        assertFalse(results.get(1).ok());
        assertTrue(results.get(1).message().contains("permissions you hold"));
        assertTrue(results.get(2).ok());
        assertEquals("Already held this role", results.get(2).message());
        verify(userManagementService, times(2)).setUserRoles(any(), any(), eq("ua@test.com"));
    }

    @Test
    void bulkMembers_removeOnlyTouchesHolders_andRefusesTheEmployeeCategoryAndOversizedBatches() {
        User holder = User.builder().id(UUID.randomUUID()).roles(new HashSet<>(Set.of(custom))).build();
        when(userRepository.findById(holder.getId())).thenReturn(Optional.of(holder));
        when(employeeRepository.findNamesByUserIds(any())).thenReturn(List.<Object[]>of(new Object[]{holder.getId(), "H"}));

        var results = service.bulkMembers(custom.getId(), List.of(holder.getId()), true, "ua@test.com");
        assertEquals("Removed", results.get(0).message());
        verify(userManagementService).setUserRoles(eq(holder.getId()), eq(new LinkedHashSet<Integer>()), eq("ua@test.com"));

        assertThrows(IllegalArgumentException.class,
                () -> service.bulkMembers(f.employee.getId(), List.of(holder.getId()), false, "ua@test.com"));
        List<UUID> tooMany = java.util.stream.IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID()).toList();
        assertThrows(IllegalArgumentException.class, () -> service.bulkMembers(custom.getId(), tooMany, false, "ua@test.com"));
    }
}
