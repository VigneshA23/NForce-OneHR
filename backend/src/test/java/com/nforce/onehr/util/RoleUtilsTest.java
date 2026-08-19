package com.nforce.onehr.util;

import com.nforce.onehr.entity.Role;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RoleUtilsTest {

    private static Role role(String code) {
        return Role.builder().id(codeToId(code)).code(code).displayName(code).build();
    }

    // Distinct ids per code so equals/hashCode (keyed on id) never collapses two roles into one.
    private static Integer codeToId(String code) {
        return switch (code) {
            case "SUPER_ADMIN" -> 1;
            case "HR_ADMIN" -> 2;
            case "MANAGER" -> 3;
            case "LEADERSHIP" -> 4;
            case "FINANCE" -> 5;
            case "DELIVERY" -> 6;
            case "EMPLOYEE" -> 7;
            default -> throw new IllegalArgumentException("Unknown code: " + code);
        };
    }

    @Test
    void superAdminAlone_returnsSuperAdmin() {
        assertEquals("SUPER_ADMIN", RoleUtils.primaryRoleCode(Set.of(role("SUPER_ADMIN")), "EMPLOYEE"));
    }

    @Test
    void managerPlusEmployee_returnsManager_regardlessOfInsertionOrder() {
        Role manager = role("MANAGER");
        Role employee = role("EMPLOYEE");

        assertEquals("MANAGER", RoleUtils.primaryRoleCode(Set.of(manager, employee), "EMPLOYEE"));
        assertEquals("MANAGER", RoleUtils.primaryRoleCode(Set.of(employee, manager), "EMPLOYEE"));
    }

    @Test
    void hrAdminPlusEmployee_returnsHrAdmin() {
        assertEquals("HR_ADMIN",
                RoleUtils.primaryRoleCode(Set.of(role("HR_ADMIN"), role("EMPLOYEE")), "EMPLOYEE"));
    }

    @Test
    void employeeAlone_returnsEmployee() {
        assertEquals("EMPLOYEE", RoleUtils.primaryRoleCode(Set.of(role("EMPLOYEE")), "EMPLOYEE"));
    }

    @Test
    void emptyOrNullRoles_returnsFallback() {
        assertEquals("EMPLOYEE", RoleUtils.primaryRoleCode(Set.of(), "EMPLOYEE"));
        assertEquals("EMPLOYEE", RoleUtils.primaryRoleCode(null, "EMPLOYEE"));
        assertNull(RoleUtils.primaryRoleCode(Set.of(), null));
    }

    /**
     * Regression test for the original bug: a HashSet<Role> built from freshly-constructed
     * Role objects (simulating repeated fresh JPA loads across login sessions) must always
     * resolve to the same priority code, never flip based on iteration order.
     */
    @Test
    void managerPlusEmployee_isStableAcrossManyFreshHashSetInstances() {
        for (int i = 0; i < 50; i++) {
            Set<Role> freshLoad = new HashSet<>();
            freshLoad.add(role("MANAGER"));
            freshLoad.add(role("EMPLOYEE"));
            assertEquals("MANAGER", RoleUtils.primaryRoleCode(freshLoad, "EMPLOYEE"));
        }
    }
}
