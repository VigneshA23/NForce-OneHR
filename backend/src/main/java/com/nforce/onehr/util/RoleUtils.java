package com.nforce.onehr.util;

import com.nforce.onehr.entity.Role;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the single "primary" / displayed role for a user who may hold multiple {@link Role}s
 * (e.g. every Manager/HR Admin also silently holds the base EMPLOYEE role — see
 * UserManagementService#rolesFor). Role has no equals/hashCode override, so a Set&lt;Role&gt;'s
 * iteration order is NOT stable across JPA sessions/queries — a fresh load can surface either
 * role first. Never pick "the" role via {@code .stream().findFirst()} on a raw Set&lt;Role&gt;;
 * use this class instead.
 */
public final class RoleUtils {

    /** Highest-priority first. Extend this list (not call sites) as new roles become assignable. */
    private static final List<String> PRIORITY_ORDER = List.of(
            "SUPER_ADMIN", "HR_ADMIN", "MANAGER", "LEADERSHIP", "FINANCE", "DELIVERY", "EMPLOYEE");

    private RoleUtils() {}

    /** Returns the highest-priority Role in the collection, or empty if null/empty. */
    public static Optional<Role> primaryRole(Collection<Role> roles) {
        if (roles == null || roles.isEmpty()) return Optional.empty();
        return roles.stream().min(Comparator.comparingInt(r -> rank(r.getCode())));
    }

    /** Returns the highest-priority role code, or {@code fallback} if null/empty. */
    public static String primaryRoleCode(Collection<Role> roles, String fallback) {
        return primaryRole(roles).map(Role::getCode).orElse(fallback);
    }

    private static int rank(String code) {
        int i = PRIORITY_ORDER.indexOf(code);
        return i >= 0 ? i : PRIORITY_ORDER.size();
    }
}
