package com.nforce.onehr.security;

import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.RolePermission;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.RolePermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Computes a user's effective permissions from the role_permissions a holder's roles grant —
 * the union across every role they hold, with "widest scope wins" implemented by granting the
 * full {@link ScopeCoverage} of each row rather than picking a single winner up front (the union
 * of coverage sets IS the widest-wins result, with no separate comparison step needed — this
 * also sidesteps DEPARTMENT/LOCATION not having a single total order between them, see
 * ScopeCoverage's own note).
 *
 * <p>Authority string format: {@code "<moduleCode>:<ACTION>:<SCOPE>"}, e.g.
 * {@code "attendance:VIEW:TEAM"} — plain hasAuthority(...) equality, no ROLE_-style prefix
 * needed (that prefix exists only because hasRole(...) auto-adds it).
 *
 * <p>Multi-role policy (deterministic, allow-only — there are no deny rules): a user's effective
 * permissions are the union over their ACTIVE roles; an inactive role contributes nothing, and only
 * permissions registered in the permissions table can ever appear (role_permissions has an FK to
 * it). The same coverage set doubles as the administration ceiling — see {@link #assertWithinCeiling}.
 */
@Service
@RequiredArgsConstructor
public class EffectivePermissionService {

    private final RolePermissionRepository rolePermissionRepository;

    @Transactional(readOnly = true)
    public List<SimpleGrantedAuthority> computeAuthorities(Collection<Role> roles) {
        List<RolePermission> grants = grantsFor(roles);
        Set<String> authorities = new LinkedHashSet<>();
        for (RolePermission rp : grants) {
            String moduleCode = rp.getPermission().getModule().getCode();
            String action = rp.getPermission().getAction();
            for (String covered : ScopeCoverage.coverage(rp.getScope())) {
                authorities.add(moduleCode + ":" + action + ":" + covered);
            }
        }
        return authorities.stream().map(SimpleGrantedAuthority::new).toList();
    }

    /**
     * The un-cascaded view for display (e.g. GET /api/me/permissions) — module -> action -> the
     * set of scopes actually granted (not the expanded coverage), so a user sees "TEAM", not
     * "OWN, TEAM" when that's the one row backing it.
     */
    @Transactional(readOnly = true)
    public Map<String, Map<String, Set<String>>> effectivePermissions(Collection<Role> roles) {
        List<RolePermission> grants = grantsFor(roles);
        Map<String, Map<String, Set<String>>> result = new TreeMap<>();
        for (RolePermission rp : grants) {
            String moduleCode = rp.getPermission().getModule().getCode();
            String action = rp.getPermission().getAction();
            result.computeIfAbsent(moduleCode, k -> new TreeMap<>())
                    .computeIfAbsent(action, k -> new TreeSet<>())
                    .add(rp.getScope());
        }
        return result;
    }

    /** Every cascaded authority the given roles grant — the set hasAuthority(...) checks against. */
    @Transactional(readOnly = true)
    public Set<String> coverage(Collection<Role> roles) {
        Set<String> result = new LinkedHashSet<>();
        computeAuthorities(roles).forEach(a -> result.add(a.getAuthority()));
        return result;
    }

    /**
     * Privilege ceiling: rejects unless {@code actorRoles} already cover every authority that
     * {@code requiredAuthorities} contains. This is what stops an administrator from granting
     * (via a role's permissions, or by assigning a role) anything they couldn't do themselves, and
     * from managing an account more privileged than their own. SUPER_ADMIN passes every check
     * without a name-based bypass: its matrix is locked (RoleAdminService) and is the superset of
     * every other role's, since nothing can be granted beyond the granter's own coverage.
     */
    public void assertWithinCeiling(Collection<Role> actorRoles, Collection<String> requiredAuthorities, String message) {
        if (!coverage(actorRoles).containsAll(requiredAuthorities)) {
            throw new IllegalArgumentException(message);
        }
    }

    /** One granted (module, action, scope) row and the active roles that grant it — for the read-only effective-permissions view. */
    public record EffectiveGrant(String moduleCode, String moduleName, String action, String scope, List<String> sources) {}

    @Transactional(readOnly = true)
    public List<EffectiveGrant> effectiveGrantsWithSources(Collection<Role> roles) {
        Map<String, EffectiveGrant> byKey = new TreeMap<>();
        for (RolePermission rp : grantsFor(roles)) {
            String moduleCode = rp.getPermission().getModule().getCode();
            String key = moduleCode + ":" + rp.getPermission().getAction() + ":" + rp.getScope();
            byKey.computeIfAbsent(key, k -> new EffectiveGrant(moduleCode, rp.getPermission().getModule().getDisplayName(),
                            rp.getPermission().getAction(), rp.getScope(), new ArrayList<>()))
                    .sources().add(rp.getRole().getDisplayName());
        }
        return List.copyOf(byKey.values());
    }

    /** Inactive roles stay assigned but grant nothing — the one place that rule is applied. */
    public static List<Role> activeRoles(Collection<Role> roles) {
        return roles.stream().filter(Role::isActive).toList();
    }

    /**
     * Service-layer permission check — the replacement for every hardcoded "is HR_ADMIN or
     * SUPER_ADMIN" branch: true if {@code user}'s active roles grant {@code authority}
     * ("module:ACTION:SCOPE", cascaded — an ALL grant satisfies a TEAM check). Works for any user,
     * not just the caller (e.g. "is this leave's owner a Super Admin-equivalent"). One query per
     * call — compute once per request, not per row.
     */
    @Transactional(readOnly = true)
    public boolean hasAuthority(User user, String authority) {
        return user != null && coverage(user.getRoles()).contains(authority);
    }

    /**
     * Ids of every ACTIVE, non-deleted user whose active roles grant module:ACTION at (at least)
     * {@code scope} — the permission-based replacement for "find all HR_ADMIN/SUPER_ADMIN users"
     * when picking eligible approvers/assignees, so a custom role holding the permission qualifies.
     */
    @Transactional(readOnly = true)
    public Set<UUID> activeUserIdsWithAuthority(String module, String action, String scope) {
        return rolePermissionRepository.findActiveUserIdsWithGrant(module, action, ScopeCoverage.grantingScopes(scope));
    }

    /** Protected (not private) only so tests can back it with the real seed matrix — see SeedMatrix. */
    protected List<RolePermission> grantsFor(Collection<Role> roles) {
        List<Integer> roleIds = activeRoles(roles).stream().map(Role::getId).toList();
        if (roleIds.isEmpty()) return List.of();
        return rolePermissionRepository.findByRoleIdsWithPermissionAndModule(roleIds);
    }
}
