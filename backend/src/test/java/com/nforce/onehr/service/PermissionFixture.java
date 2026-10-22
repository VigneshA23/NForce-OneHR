package com.nforce.onehr.service;

import com.nforce.onehr.entity.Permission;
import com.nforce.onehr.entity.PermissionModule;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.RolePermission;
import com.nforce.onehr.repository.RolePermissionRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * A small in-memory role_permissions table backing a mocked RolePermissionRepository, so tests can
 * run the REAL EffectivePermissionService (union, inactive-role exclusion, scope cascade, ceiling)
 * instead of stubbing its answers. Mirrors the V225/V232 seed shape for the roles it defines.
 */
final class PermissionFixture {

    final Map<String, PermissionModule> modules = new HashMap<>();
    final Map<String, Permission> permissions = new HashMap<>();
    final List<RolePermission> rows = new ArrayList<>();

    final Role employee = Role.builder().id(1).code("EMPLOYEE").displayName("Employee").system(true).phase1(true).build();
    final Role manager = Role.builder().id(2).code("MANAGER").displayName("Manager").system(true).phase1(true).build();
    final Role hrAdmin = Role.builder().id(3).code("HR_ADMIN").displayName("HR Admin").system(true).phase1(true).build();
    final Role superAdmin = Role.builder().id(4).code("SUPER_ADMIN").displayName("Super Admin").system(true).phase1(true).build();
    final Role delivery = Role.builder().id(5).code("DELIVERY").displayName("Delivery").system(true).phase1(false).build();
    /** Custom "user admin": can manage users and assign roles, but holds no HR-module permissions. */
    final Role userAdmin = Role.builder().id(10).code("CUSTOM_USER_ADMIN").displayName("User Admin").build();
    final Role inactiveCustom = Role.builder().id(11).code("CUSTOM_OLD").displayName("Old Role").active(false).build();

    PermissionFixture() {
        module("leave", false);
        module("employees", true);
        module("user_management", true);
        module("user_roles", true);
        module("roles", true);
        for (String a : List.of("VIEW", "APPROVE", "CONFIGURE")) permission("leave", a);
        for (String a : List.of("VIEW", "EDIT", "CREATE")) permission("employees", a);
        for (String a : List.of("VIEW", "CREATE", "EDIT", "DELETE")) permission("user_management", a);
        for (String a : List.of("VIEW", "EDIT")) permission("user_roles", a);
        for (String a : List.of("VIEW", "CREATE", "EDIT", "DELETE", "CONFIGURE")) permission("roles", a);

        grant(manager, "leave", "APPROVE", "TEAM");
        grant(hrAdmin, "leave", "APPROVE", "ALL");
        grant(hrAdmin, "employees", "EDIT", "ALL");
        for (Permission p : permissions.values()) grant(superAdmin, p.getModule().getCode(), p.getAction(), "ALL");
        for (String a : List.of("VIEW", "CREATE", "EDIT")) grant(userAdmin, "user_management", a, "ALL");
        grant(userAdmin, "user_roles", "EDIT", "ALL");
        grant(userAdmin, "roles", "CONFIGURE", "ALL");
        grant(inactiveCustom, "user_management", "DELETE", "ALL");
    }

    private void module(String code, boolean ready) {
        modules.put(code, PermissionModule.builder().id(modules.size() + 1).code(code).displayName(code).customRoleReady(ready).build());
    }

    private void permission(String module, String action) {
        permissions.put(module + ":" + action, Permission.builder().id(permissions.size() + 1)
                .module(modules.get(module)).action(action).build());
    }

    RolePermission grant(Role role, String module, String action, String scope) {
        RolePermission rp = RolePermission.builder().id((long) rows.size() + 1).role(role)
                .permission(permissions.get(module + ":" + action)).scope(scope).build();
        rows.add(rp);
        return rp;
    }

    /** Wires the repository mock to answer from {@link #rows}. */
    @SuppressWarnings("unchecked")
    void bind(RolePermissionRepository repo) {
        lenient().when(repo.findByRoleIdsWithPermissionAndModule(any())).thenAnswer(inv -> {
            Collection<Integer> ids = inv.getArgument(0, Collection.class);
            return rows.stream().filter(rp -> ids.contains(rp.getRole().getId())).toList();
        });
    }
}
