package com.nforce.onehr.util;

import com.nforce.onehr.entity.Role;
import com.nforce.onehr.util.RoleCategorization.Category;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The role-list categorisation rule, identical whether or not a legacy EMPLOYEE user_roles row
 * exists: assigned roles only, EMPLOYEE only as the default for people with none.
 */
class RoleCategorizationTest {

    private static int nextId = 1;

    private static Role role(String code, String name) {
        return Role.builder().id(nextId++).code(code).displayName(name).build();
    }

    private static final String EMP = "EMPLOYEE";

    private static List<String> codes(Set<Role> roles) {
        return RoleCategorization.categoriesOf(roles).stream().map(Category::code).toList();
    }

    @Test
    void ordinaryEmployee_noRoleRowAtAll_isEmployee() {
        assertEquals(List.of(EMP), codes(Set.of()));
        assertTrue(RoleCategorization.isDefaultEmployee(Set.of()));
        assertEquals(List.of(EMP), codes(null));
    }

    @Test
    void ordinaryEmployee_withLegacyEmployeeRow_isStillJustEmployee() {
        assertEquals(List.of(EMP), codes(Set.of(role(EMP, "Employee"))));
        assertTrue(RoleCategorization.isDefaultEmployee(Set.of(role(EMP, "Employee"))));
    }

    @Test
    void superAdmin_appearsOnlyUnderSuperAdmin_notEmployee() {
        assertEquals(List.of("SUPER_ADMIN"), codes(Set.of(role("SUPER_ADMIN", "Super Admin"))));
        // …with the legacy EMPLOYEE row too: still not in the Employee list.
        assertEquals(List.of("SUPER_ADMIN"), codes(Set.of(role("SUPER_ADMIN", "Super Admin"), role(EMP, "Employee"))));
    }

    @Test
    void manager_and_hrAdmin_appearOnlyUnderTheirOwnRole() {
        assertEquals(List.of("MANAGER"), codes(Set.of(role("MANAGER", "Manager"), role(EMP, "Employee"))));
        assertEquals(List.of("HR_ADMIN"), codes(Set.of(role("HR_ADMIN", "HR Admin"), role(EMP, "Employee"))));
    }

    @Test
    void customRoleOnly_isUnderThatRole_notEmployee() {
        assertEquals(List.of("CUSTOM_PAYROLL"), codes(Set.of(role("CUSTOM_PAYROLL", "Payroll"))));
        assertFalse(RoleCategorization.isDefaultEmployee(Set.of(role("CUSTOM_PAYROLL", "Payroll"), role(EMP, "Employee"))));
    }

    @Test
    void multipleAdministrativeAndCustomRoles_appearUnderEach_inPriorityThenNameOrder() {
        Set<Role> roles = Set.of(role("CUSTOM_ZED", "Zed"), role("MANAGER", "Manager"),
                role("CUSTOM_ALPHA", "Alpha"), role("HR_ADMIN", "HR Admin"), role(EMP, "Employee"));

        assertEquals(List.of("HR_ADMIN", "MANAGER", "CUSTOM_ALPHA", "CUSTOM_ZED"), codes(roles));
    }

    @Test
    void afterRemovingTheOnlyAssignedRole_theUserFallsBackToEmployee() {
        Role manager = role("MANAGER", "Manager");
        assertEquals(List.of("MANAGER"), codes(Set.of(manager)));
        assertEquals(List.of(EMP), codes(Set.of())); // role removed
    }
}
