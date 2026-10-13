package com.nforce.onehr.security;

import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.RolePermission;
import com.nforce.onehr.repository.RolePermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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

    private List<RolePermission> grantsFor(Collection<Role> roles) {
        List<Integer> roleIds = roles.stream().map(Role::getId).toList();
        if (roleIds.isEmpty()) return List.of();
        return rolePermissionRepository.findByRoleIdsWithPermissionAndModule(roleIds);
    }
}
