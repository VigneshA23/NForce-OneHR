package com.nforce.onehr.service;

import com.nforce.onehr.dto.roles.RoleDtos.*;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Permission;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.RolePermission;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PermissionRepository;
import com.nforce.onehr.repository.RolePermissionRepository;
import com.nforce.onehr.repository.RoleRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Roles & Permissions administration: custom-role CRUD, a role's permission matrix, and role
 * membership (which delegates to {@link UserManagementService#setUserRoles} so every assignment
 * path shares one set of guards).
 *
 * <p>Protection rules, all enforced here (the UI only mirrors them):
 * <ul>
 *   <li>System roles (V232 is_system) can't be renamed, deactivated or deleted.</li>
 *   <li>SUPER_ADMIN's permissions are locked — it is the protected all-access role that guarantees
 *       there is always an administrator who can undo any other change.</li>
 *   <li>Only a Super Admin can change a system role's permissions (Employee/Manager/HR Admin
 *       apply org-wide); a custom-role administrator with roles:CONFIGURE manages custom roles only.
 *       A deliberate role-code check — a restriction, never a bypass.</li>
 *   <li>Only permissions registered in the catalogue, in modules marked custom_role_ready, can be
 *       changed; rows for other modules are left exactly as they are.</li>
 *   <li>Privilege ceiling: an actor can only add, change, or remove a grant they hold themselves (at
 *       that scope or wider), and can only toggle/delete a role within their own coverage.</li>
 * </ul>
 * Permission changes take effect on every holder's next request (authorities are re-resolved per
 * request — see CustomUserDetailsService), so no session invalidation is needed for them.
 */
@Service
@RequiredArgsConstructor
public class RoleAdminService {

    static final Set<String> SCOPES = Set.of(RolePermission.OWN, RolePermission.TEAM,
            RolePermission.DEPARTMENT, RolePermission.LOCATION, RolePermission.ALL);
    private static final String CONFIGURE_AUTHORITY = "roles:CONFIGURE:ALL";

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final EffectivePermissionService effectivePermissionService;
    private final UserManagementService userManagementService;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    // ─── Reads ──────────────────────────────────────────────────────────────────

    /** Assignable system roles + every custom role. Seeded placeholders (DELIVERY etc.) are hidden. */
    @Transactional(readOnly = true)
    public List<RoleSummary> list() {
        Map<Integer, Long> users = toCountMap(userRepository.countHoldersByRole());
        Map<Integer, Long> grants = toCountMap(rolePermissionRepository.countByRole());
        return roleRepository.findAll().stream()
                .filter(RoleAdminService::isListed)
                .sorted(Comparator.comparing(Role::isSystem).reversed().thenComparing(Role::getId))
                .map(r -> summary(r, users.getOrDefault(r.getId(), 0L), grants.getOrDefault(r.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public RoleDetail get(Integer roleId) {
        Role role = requireListedRole(roleId);
        List<Grant> grants = grantsOf(roleId).stream().map(RoleAdminService::toGrant).sorted(GRANT_ORDER).toList();
        List<User> holders = userRepository.findHoldersOfRole(roleId);
        Map<UUID, Employee> employees = employeeRepository.findAllById(holders.stream().map(User::getId).toList())
                .stream().collect(Collectors.toMap(Employee::getUserId, Function.identity()));
        List<RoleMember> members = holders.stream()
                .map(u -> {
                    Employee e = employees.get(u.getId());
                    return new RoleMember(u.getId(), e != null ? e.getFullName() : u.getEmail(), u.getEmail(),
                            e != null ? e.getEmployeeCode() : null, u.isActive());
                })
                .sorted(Comparator.comparing(RoleMember::fullName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new RoleDetail(summary(role, members.size(), grants.size()), grants, members);
    }

    /** The registered permission catalogue, grouped by module, in display order. */
    @Transactional(readOnly = true)
    public List<CatalogueModule> catalogue() {
        Map<String, List<Permission>> byModule = new LinkedHashMap<>();
        for (Permission p : permissionRepository.findAllWithModule()) {
            byModule.computeIfAbsent(p.getModule().getCode(), k -> new ArrayList<>()).add(p);
        }
        return byModule.values().stream()
                .map(ps -> new CatalogueModule(ps.get(0).getModule().getCode(), ps.get(0).getModule().getDisplayName(),
                        ps.get(0).getModule().isCustomRoleReady(),
                        ps.stream().map(Permission::getAction).sorted(Comparator.comparingInt(ACTION_ORDER::indexOf)).toList()))
                .toList();
    }

    // ─── Role CRUD ──────────────────────────────────────────────────────────────

    @Transactional
    public RoleDetail create(CreateRoleRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        String name = req.name().trim();
        if (roleRepository.existsByDisplayNameIgnoreCase(name))
            throw new IllegalArgumentException("A role named '" + name + "' already exists.");
        Role role = roleRepository.save(Role.builder()
                .code(newCode(name))
                .displayName(name)
                .description(blankToNull(req.description()))
                .system(false)
                .phase1(false)
                .active(true)
                .build());
        List<Grant> grants = req.grants() == null ? List.of() : req.grants();
        if (!grants.isEmpty()) {
            requireAuthority(actor, CONFIGURE_AUTHORITY);
            applyGrants(actor, role, grants);
        }
        auditService.log(actor.getId(), "ROLE_CREATED", null, null,
                auditSnapshot.toJson(Map.of("role", role.getCode(), "name", name, "permissions", authorityStrings(grantsOf(role.getId())))));
        return get(role.getId());
    }

    @Transactional
    public RoleDetail update(Integer roleId, UpdateRoleRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        Role role = requireCustomRole(roleId, "renamed");
        requireVersion(role, req.version());
        String name = req.name().trim();
        if (roleRepository.existsByDisplayNameIgnoreCaseAndIdNot(name, roleId))
            throw new IllegalArgumentException("A role named '" + name + "' already exists.");
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("name", role.getDisplayName());
        before.put("description", role.getDescription());
        role.setDisplayName(name);
        role.setDescription(blankToNull(req.description()));
        roleRepository.save(role);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("role", role.getCode());
        after.put("name", name);
        after.put("description", role.getDescription());
        auditService.log(actor.getId(), "ROLE_UPDATED", null, auditSnapshot.toJson(before), auditSnapshot.toJson(after));
        return get(roleId);
    }

    /** Deactivated roles stay assigned but grant nothing (EffectivePermissionService#activeRoles). */
    @Transactional
    public RoleDetail setActive(Integer roleId, boolean active, int expectedVersion, String actorEmail) {
        User actor = requireActor(actorEmail);
        Role role = requireCustomRole(roleId, active ? "activated" : "deactivated");
        requireVersion(role, expectedVersion);
        if (role.isActive() == active) return get(roleId);
        assertRoleWithinCeiling(actor, role);
        role.setActive(active);
        roleRepository.save(role);
        auditService.log(actor.getId(), active ? "ROLE_ACTIVATED" : "ROLE_DEACTIVATED", null,
                auditSnapshot.toJson(Map.of("role", role.getCode(), "active", !active)),
                auditSnapshot.toJson(Map.of("role", role.getCode(), "active", active)));
        return get(roleId);
    }

    /**
     * Deletes a custom role. A role still assigned to users is refused unless
     * {@code removeFromUsers}: then it is first taken off every holder through
     * UserManagementService#setUserRoles — the same guards (role-assignment permission, privilege
     * ceiling, no self-change), session sign-out and ROLE_CHANGED audit as Manage Roles — all in this
     * one transaction, so any refusal leaves both the role and every assignment untouched.
     */
    @Transactional
    public void delete(Integer roleId, boolean removeFromUsers, String actorEmail) {
        User actor = requireActor(actorEmail);
        Role role = requireCustomRole(roleId, "deleted");
        assertRoleWithinCeiling(actor, role);
        List<User> holders = userRepository.findHoldersOfRole(roleId);
        if (!holders.isEmpty() && !removeFromUsers)
            throw new IllegalStateException("This role is assigned to " + holders.size() + " user" + (holders.size() == 1 ? "" : "s")
                    + ". Remove it from them first, or deactivate the role instead.");
        if (holders.stream().anyMatch(u -> u.getId().equals(actor.getId())))
            throw new IllegalArgumentException("You hold this role yourself. Ask another administrator to remove it from you first.");
        for (User holder : holders) {
            Set<Integer> remaining = holder.getRoles().stream().map(Role::getId)
                    .filter(id -> !id.equals(roleId)).collect(Collectors.toCollection(LinkedHashSet::new));
            userManagementService.setUserRoles(holder.getId(), remaining, actorEmail);
        }
        List<String> permissions = authorityStrings(grantsOf(roleId));
        rolePermissionRepository.deleteByRole_Id(roleId);
        roleRepository.deleteAssignments(roleId);
        roleRepository.delete(role);
        auditService.log(actor.getId(), "ROLE_DELETED", null,
                auditSnapshot.toJson(Map.of("role", role.getCode(), "name", role.getDisplayName(), "permissions", permissions)), null);
    }

    // ─── Permissions ────────────────────────────────────────────────────────────

    /** Replaces the role's grants in configurable modules with exactly {@code grants}. */
    @Transactional
    public RoleDetail setPermissions(Integer roleId, List<Grant> grants, int expectedVersion, String actorEmail) {
        User actor = requireActor(actorEmail);
        Role role = requireListedRole(roleId);
        requireVersion(role, expectedVersion);
        if (role.isSystem() && EffectivePermissionService.activeRoles(actor.getRoles()).stream()
                .noneMatch(r -> "SUPER_ADMIN".equals(r.getCode())))
            throw new IllegalArgumentException("Only a Super Admin can change the permissions of a system role.");
        List<String> before = authorityStrings(grantsOf(roleId));
        applyGrants(actor, role, grants);
        List<String> after = authorityStrings(grantsOf(roleId));
        if (!before.equals(after)) {
            role.setUpdatedAt(LocalDateTime.now());
            roleRepository.save(role);
            auditService.log(actor.getId(), "ROLE_PERMISSIONS_UPDATED", null,
                    auditSnapshot.toJson(Map.of("role", role.getCode(), "permissions", before)),
                    auditSnapshot.toJson(Map.of("role", role.getCode(), "permissions", after)));
        }
        return get(roleId);
    }

    /**
     * Validates every requested grant, then diffs against the role's current rows in configurable
     * modules only: changed scopes are updated in place (keeps uq_role_permission happy within one
     * flush), removed rows deleted, new rows inserted. Every touched row — before and after value —
     * must be within the actor's own coverage. Runs inside the caller's transaction, so any
     * rejection rolls the whole change back.
     */
    private void applyGrants(User actor, Role role, List<Grant> requested) {
        if ("SUPER_ADMIN".equals(role.getCode()))
            throw new IllegalArgumentException("Super Admin's permissions are protected and cannot be changed.");

        Map<String, Permission> registered = permissionRepository.findAllWithModule().stream()
                .collect(Collectors.toMap(p -> key(p.getModule().getCode(), p.getAction()), Function.identity()));
        Map<String, String> wanted = new LinkedHashMap<>(); // module:ACTION -> scope
        for (Grant g : requested) {
            String k = key(g.module(), g.action());
            Permission p = registered.get(k);
            if (p == null) throw new IllegalArgumentException("Unknown permission: " + k);
            if (!SCOPES.contains(g.scope())) throw new IllegalArgumentException("Invalid scope '" + g.scope() + "' for " + k);
            if (!p.getModule().isCustomRoleReady())
                throw new IllegalArgumentException(p.getModule().getDisplayName() + " permissions can't be configured yet.");
            if (wanted.put(k, g.scope()) != null) throw new IllegalArgumentException("Duplicate permission: " + k);
        }

        Map<String, RolePermission> current = grantsOf(role.getId()).stream()
                .filter(rp -> rp.getPermission().getModule().isCustomRoleReady())
                .collect(Collectors.toMap(rp -> key(rp.getPermission().getModule().getCode(), rp.getPermission().getAction()),
                        Function.identity()));

        Set<String> touched = new HashSet<>();
        List<RolePermission> toDelete = new ArrayList<>();
        List<RolePermission> toSave = new ArrayList<>();
        for (Map.Entry<String, RolePermission> e : current.entrySet()) {
            RolePermission rp = e.getValue();
            String scope = wanted.get(e.getKey());
            if (scope == null) {
                touched.add(e.getKey() + ":" + rp.getScope());
                toDelete.add(rp);
            } else if (!scope.equals(rp.getScope())) {
                touched.add(e.getKey() + ":" + rp.getScope());
                touched.add(e.getKey() + ":" + scope);
                rp.setScope(scope);
                toSave.add(rp);
            }
        }
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            if (current.containsKey(e.getKey())) continue;
            touched.add(e.getKey() + ":" + e.getValue());
            toSave.add(RolePermission.builder().role(role).permission(registered.get(e.getKey())).scope(e.getValue()).build());
        }
        if (touched.isEmpty()) return;
        effectivePermissionService.assertWithinCeiling(actor.getRoles(), touched,
                "You can only grant or remove permissions you hold yourself, at the same scope or wider.");
        rolePermissionRepository.deleteAll(toDelete);
        rolePermissionRepository.saveAll(toSave);
    }

    // ─── Membership ─────────────────────────────────────────────────────────────

    @Transactional
    public RoleDetail addMember(Integer roleId, UUID userId, String actorEmail) {
        requireListedRole(roleId);
        Set<Integer> ids = currentRoleIds(userId);
        if (ids.add(roleId)) userManagementService.setUserRoles(userId, ids, actorEmail);
        return get(roleId);
    }

    @Transactional
    public RoleDetail removeMember(Integer roleId, UUID userId, String actorEmail) {
        Role role = requireListedRole(roleId);
        if ("EMPLOYEE".equals(role.getCode()))
            throw new IllegalArgumentException("Every user holds the Employee role; it can't be removed.");
        Set<Integer> ids = currentRoleIds(userId);
        if (ids.remove(roleId)) userManagementService.setUserRoles(userId, ids, actorEmail);
        return get(roleId);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static final List<String> ACTION_ORDER = List.of(
            Permission.VIEW, Permission.CREATE, Permission.EDIT, Permission.DELETE,
            Permission.APPROVE, Permission.REVERT, Permission.EXPORT, Permission.CONFIGURE);
    private static final Comparator<Grant> GRANT_ORDER = Comparator.comparing(Grant::module)
            .thenComparingInt(g -> ACTION_ORDER.indexOf(g.action()));

    private static boolean isListed(Role r) {
        return !r.isSystem() || r.isPhase1();
    }

    private RoleSummary summary(Role r, long userCount, long permissionCount) {
        return new RoleSummary(r.getId(), r.getCode(), r.getDisplayName(), r.getDescription(), r.isSystem(), r.isActive(),
                !"SUPER_ADMIN".equals(r.getCode()), !r.isSystem(), userCount, permissionCount, r.getCreatedAt(), r.getUpdatedAt(),
                r.getVersion());
    }

    /**
     * Two admins editing the same role: the second save carries the version it loaded, which the
     * first save already bumped, so it's rejected (409) instead of silently overwriting. @Version
     * on Role still catches a race between two requests that both passed this check.
     */
    private static void requireVersion(Role role, int expectedVersion) {
        if (role.getVersion() != expectedVersion)
            throw new IllegalStateException("This role was changed by someone else since you opened it. Reload to see the latest version, then try again.");
    }

    private Role requireListedRole(Integer roleId) {
        return roleRepository.findById(roleId).filter(RoleAdminService::isListed)
                .orElseThrow(() -> new NoSuchElementException("Role not found"));
    }

    private Role requireCustomRole(Integer roleId, String verb) {
        Role role = requireListedRole(roleId);
        if (role.isSystem()) throw new IllegalArgumentException("System roles can't be " + verb + ".");
        return role;
    }

    private void assertRoleWithinCeiling(User actor, Role role) {
        effectivePermissionService.assertWithinCeiling(actor.getRoles(), effectivePermissionService.coverage(List.of(role)),
                "This role has permissions beyond your own.");
    }

    private void requireAuthority(User actor, String authority) {
        if (!effectivePermissionService.coverage(actor.getRoles()).contains(authority))
            throw new AccessDeniedException("Missing " + authority);
    }

    private Set<Integer> currentRoleIds(UUID userId) {
        User user = userRepository.findById(userId).filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new NoSuchElementException("User not found"));
        return user.getRoles().stream().map(Role::getId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<RolePermission> grantsOf(Integer roleId) {
        return rolePermissionRepository.findByRoleIdsWithPermissionAndModule(List.of(roleId));
    }

    private static Grant toGrant(RolePermission rp) {
        return new Grant(rp.getPermission().getModule().getCode(), rp.getPermission().getAction(), rp.getScope());
    }

    private static List<String> authorityStrings(List<RolePermission> rows) {
        return rows.stream().map(rp -> key(rp.getPermission().getModule().getCode(), rp.getPermission().getAction()) + ":" + rp.getScope())
                .sorted().toList();
    }

    private static String key(String module, String action) {
        return module + ":" + action;
    }

    /** Stable, never-reused machine code derived from the first name — "CUSTOM_" keeps it disjoint from every system code. */
    private String newCode(String name) {
        String slug = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        String base = "CUSTOM_" + (slug.isEmpty() ? "ROLE" : slug.substring(0, Math.min(slug.length(), 20)));
        String code = base;
        for (int i = 2; roleRepository.existsByCode(code); i++) code = base + "_" + i;
        return code;
    }

    private static Map<Integer, Long> toCountMap(List<Object[]> rows) {
        Map<Integer, Long> m = new HashMap<>();
        for (Object[] row : rows) m.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        return m;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("Actor not found"));
    }
}
