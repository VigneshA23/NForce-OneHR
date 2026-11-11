package com.nforce.onehr.util;

import com.nforce.onehr.entity.Role;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * The ONE definition of which role list(s) a user belongs to, shared by every screen that groups
 * people by role (User Management filter/badges, Roles & Permissions membership and counts):
 * <ul>
 *   <li>a user's categories are their ASSIGNED roles, excluding EMPLOYEE — an administrative or
 *       custom role never also lands the user in the default Employee list;</li>
 *   <li>a user with no assigned role at all is categorised as EMPLOYEE (the default category).</li>
 * </ul>
 * EMPLOYEE is a category, not an authorization role: baseline self-service access never depends on
 * holding it (see V225), so categorisation works identically whether or not a legacy EMPLOYEE
 * {@code user_roles} row exists. Only the Employee MASTER (every employee record) is unaffected.
 */
public final class RoleCategorization {

    public static final String EMPLOYEE = "EMPLOYEE";

    /** A role-list entry. {@code code}/{@code name} of the default category are EMPLOYEE / Employee. */
    public record Category(String code, String name) {}

    private static final Category DEFAULT = new Category(EMPLOYEE, "Employee");

    private RoleCategorization() {}

    /** Assigned roles first (built-in by priority, then custom by name), or [Employee] if none. */
    public static List<Category> categoriesOf(Collection<Role> roles) {
        List<Category> assigned = roles == null ? List.of() : roles.stream()
                .filter(r -> !EMPLOYEE.equals(r.getCode()))
                .sorted(Comparator.comparingInt((Role r) -> builtInRank(r.getCode()))
                        .thenComparing(r -> r.getDisplayName() == null ? "" : r.getDisplayName(), String.CASE_INSENSITIVE_ORDER))
                .map(r -> new Category(r.getCode(), r.getDisplayName()))
                .toList();
        return assigned.isEmpty() ? List.of(DEFAULT) : assigned;
    }

    /** True when the user belongs to the default Employee category (no assigned role other than EMPLOYEE). */
    public static boolean isDefaultEmployee(Collection<Role> roles) {
        return categoriesOf(roles).equals(List.of(DEFAULT));
    }

    private static int builtInRank(String code) {
        return switch (code) {
            case "SUPER_ADMIN" -> 0;
            case "HR_ADMIN" -> 1;
            case "MANAGER" -> 2;
            case "LEADERSHIP" -> 3;
            case "FINANCE" -> 4;
            case "DELIVERY" -> 5;
            default -> 6; // custom roles after the built-ins
        };
    }
}
