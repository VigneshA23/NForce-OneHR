package com.nforce.onehr.service;

import com.nforce.onehr.dto.roles.RoleDtos.*;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Permission;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.RolePermission;
import com.nforce.onehr.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.entity.AuditLog;
import com.nforce.onehr.repository.AuditLogRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.security.ScopeCoverage;
import org.springframework.data.domain.PageRequest;
import com.nforce.onehr.repository.PermissionRepository;
import com.nforce.onehr.repository.RolePermissionRepository;
import com.nforce.onehr.repository.RoleRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.security.ForceLogoutBroadcaster;
import com.nforce.onehr.security.PermissionCatalogue;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final ForceLogoutBroadcaster forceLogoutBroadcaster;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final PermissionCatalogue permissionCatalogue;

    // ─── Reads ──────────────────────────────────────────────────────────────────

    /** Assignable system roles + every custom role. Seeded placeholders (DELIVERY etc.) are hidden. */
    @Transactional(readOnly = true)
    public List<RoleSummary> list() {
        Map<Integer, Long> users = toCountMap(userRepository.countHoldersByRole());
        Map<Integer, Long> grants = toCountMap(rolePermissionRepository.countByRole());
        return roleRepository.findAll().stream()
                .filter(RoleAdminService::isListed)
                .sorted(Comparator.comparing(Role::isSystem).reversed().thenComparing(Role::getId))
                .map(r -> summary(r, isDefaultCategory(r) ? userRepository.countUsersWithoutAssignedRole()
                        : users.getOrDefault(r.getId(), 0L), grants.getOrDefault(r.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public RoleDetail get(Integer roleId) {
        Role role = requireListedRole(roleId);
        List<Grant> grants = grantsOf(roleId).stream().map(RoleAdminService::toGrant).sorted(GRANT_ORDER).toList();
        // EMPLOYEE is the default category, not an assignable role: its "members" are the people with
        // no other role (see RoleCategorization), so admins/custom-role holders are never listed here.
        List<User> holders = isDefaultCategory(role) ? userRepository.findUsersWithoutAssignedRole()
                : userRepository.findHoldersOfRole(roleId);
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

    /**
     * The catalogue as a Module > Submodule > Section tree. The hierarchy, labels, sensitivity and per-action
     * scope ranges come from permission-catalogue.yml; {@code configurable} and which actions are really
     * registered come from the database, so a node that is in the file but not yet migrated shows read-only
     * with no actions rather than offering grants that would be refused.
     */
    @Transactional(readOnly = true)
    public List<CatalogueNode> catalogueTree() {
        Set<String> registered = new HashSet<>();
        Map<String, Boolean> ready = new HashMap<>();
        for (Permission p : permissionRepository.findAllWithModule()) {
            registered.add(key(p.getModule().getCode(), p.getAction()));
            ready.put(p.getModule().getCode(), p.getModule().isCustomRoleReady());
        }
        return permissionCatalogue.modules().stream().map(n -> toTreeNode(n, registered, ready)).toList();
    }

    private CatalogueNode toTreeNode(PermissionCatalogue.Node n, Set<String> registered, Map<String, Boolean> ready) {
        List<CatalogueAction> actions = n.actions().entrySet().stream()
                .filter(e -> registered.contains(key(n.code(), e.getKey())))
                .map(e -> new CatalogueAction(e.getKey(), e.getValue().min(), e.getValue().max()))
                .toList();
        return new CatalogueNode(n.code(), n.name(), n.type(), n.description(), n.high(),
                n.isLeaf() && !actions.isEmpty() && ready.getOrDefault(n.code(), false), actions,
                n.children().stream().map(c -> toTreeNode(c, registered, ready)).toList());
    }

    // ─── History & reverse lookup ───────────────────────────────────────────────

    private static final List<String> ROLE_AUDIT_ACTIONS = List.of(
            "ROLE_CREATED", "ROLE_UPDATED", "ROLE_ACTIVATED", "ROLE_DEACTIVATED", "ROLE_PERMISSIONS_UPDATED");
    private static final int HISTORY_SCAN = 500;
    private static final int HISTORY_LIMIT = 50;

    /** The role's audited changes, newest first, with permission edits expanded into what was added/removed. */
    @Transactional(readOnly = true)
    public List<RoleHistoryEntry> history(Integer roleId) {
        Role role = requireListedRole(roleId);
        List<AuditLog> rows = auditLogRepository.findByActionInOrderByOccurredAtDesc(ROLE_AUDIT_ACTIONS, PageRequest.of(0, HISTORY_SCAN));
        List<AuditLog> mine = new ArrayList<>();
        Map<Long, JsonNode[]> parsed = new HashMap<>();
        for (AuditLog row : rows) {
            JsonNode before = json(row.getBeforeState());
            JsonNode after = json(row.getAfterState());
            String code = text(after, "role") != null ? text(after, "role") : text(before, "role");
            if (!role.getCode().equals(code)) continue;
            mine.add(row);
            parsed.put(row.getId(), new JsonNode[]{before, after});
            if (mine.size() == HISTORY_LIMIT) break;
        }
        Set<UUID> actorIds = mine.stream().map(AuditLog::getActorId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> names = actorIds.isEmpty() ? Map.of() : employeeRepository.findNamesByUserIds(actorIds).stream()
                .collect(Collectors.toMap(r -> (UUID) r[0], r -> (String) r[1], (a, b) -> a));
        List<RoleHistoryEntry> out = new ArrayList<>();
        for (AuditLog row : mine) {
            JsonNode[] state = parsed.get(row.getId());
            List<String> beforePerms = strings(state[0], "permissions");
            List<String> afterPerms = strings(state[1], "permissions");
            List<String> added = new ArrayList<>(afterPerms);
            added.removeAll(beforePerms);
            List<String> removed = new ArrayList<>(beforePerms);
            removed.removeAll(afterPerms);
            String summary = switch (row.getAction()) {
                case "ROLE_CREATED" -> "Role created";
                case "ROLE_UPDATED" -> "Details changed" + renameSuffix(state[0], state[1]);
                case "ROLE_ACTIVATED" -> "Role activated";
                case "ROLE_DEACTIVATED" -> "Role deactivated — holders keep it but it grants nothing";
                default -> "Permissions changed";
            };
            out.add(new RoleHistoryEntry(row.getId(), row.getOccurredAt(), row.getAction(),
                    row.getActorId() == null ? null : names.getOrDefault(row.getActorId(), "Unknown"),
                    summary, "ROLE_CREATED".equals(row.getAction()) ? afterPerms : added,
                    "ROLE_PERMISSIONS_UPDATED".equals(row.getAction()) ? removed : List.of()));
        }
        return out;
    }

    /** Who holds {@code module:action} through an active role — the reverse of the role editor. */
    @Transactional(readOnly = true)
    public List<PermissionHolder> holders(String module, String action) {
        boolean registered = permissionRepository.findAllWithModule().stream()
                .anyMatch(p -> p.getModule().getCode().equals(module) && p.getAction().equals(action));
        if (!registered) throw new NoSuchElementException("Unknown permission: " + module + ":" + action);
        record Acc(User user, String[] scope, Set<String> roles) {}
        Map<UUID, Acc> byUser = new LinkedHashMap<>();
        for (RolePermission rp : rolePermissionRepository.findActiveGrants(module, action)) {
            for (User u : userRepository.findHoldersOfRole(rp.getRole().getId())) {
                Acc acc = byUser.computeIfAbsent(u.getId(), id -> new Acc(u, new String[]{rp.getScope()}, new TreeSet<>()));
                acc.roles().add(rp.getRole().getDisplayName());
                if (ScopeCoverage.coverage(rp.getScope()).size() > ScopeCoverage.coverage(acc.scope()[0]).size())
                    acc.scope()[0] = rp.getScope();
            }
        }
        Map<UUID, String> names = byUser.isEmpty() ? Map.of() : employeeRepository.findNamesByUserIds(byUser.keySet()).stream()
                .collect(Collectors.toMap(r -> (UUID) r[0], r -> (String) r[1], (a, b) -> a));
        return byUser.values().stream()
                .map(a -> new PermissionHolder(a.user().getId(), names.getOrDefault(a.user().getId(), a.user().getEmail()),
                        a.user().getEmail(), a.user().isActive(), a.scope()[0], List.copyOf(a.roles())))
                .sorted(Comparator.comparing(PermissionHolder::fullName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private JsonNode json(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return objectMapper.readTree(raw); } catch (Exception e) { return null; }
    }

    private static String text(JsonNode n, String field) {
        return n != null && n.hasNonNull(field) ? n.get(field).asText() : null;
    }

    private static List<String> strings(JsonNode n, String field) {
        List<String> out = new ArrayList<>();
        if (n != null && n.has(field) && n.get(field).isArray()) n.get(field).forEach(x -> out.add(x.asText()));
        return out;
    }

    private static String renameSuffix(JsonNode before, JsonNode after) {
        String from = text(before, "name"), to = text(after, "name");
        return from != null && to != null && !from.equals(to) ? " (renamed “" + from + "” → “" + to + "”)" : "";
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
        notifyHoldersAfterCommit(roleId);
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
            notifyHoldersAfterCommit(roleId);
            // Authorities added or removed on a HIGH-sensitivity node (roles, user management, payroll, ...)
            // are called out so the history and any alerting can single them out.
            Set<String> changed = new TreeSet<>(after);
            changed.addAll(before);
            Set<String> unchanged = new HashSet<>(after);
            unchanged.retainAll(before);
            changed.removeAll(unchanged);
            List<String> highImpact = changed.stream().filter(this::isHighImpact).toList();
            auditService.log(actor.getId(), "ROLE_PERMISSIONS_UPDATED", null,
                    auditSnapshot.toJson(Map.of("role", role.getCode(), "permissions", before)),
                    auditSnapshot.toJson(highImpact.isEmpty()
                            ? Map.of("role", role.getCode(), "permissions", after)
                            : Map.of("role", role.getCode(), "permissions", after, "highImpact", highImpact)));
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
        // Granting permissions to "everyone with no additional role" would be an implicit privilege:
        // baseline access is not a role grant, so extra capabilities go in an explicit custom role.
        if (isDefaultCategory(role))
            throw new IllegalArgumentException("Employee is the default category and can't hold permissions. Create a custom role and assign it to the people who need extra access.");

        Map<String, Permission> registered = permissionRepository.findAllWithModule().stream()
                .collect(Collectors.toMap(p -> key(p.getModule().getCode(), p.getAction()), Function.identity()));
        Map<String, String> wanted = new LinkedHashMap<>(); // module:ACTION -> scope
        for (Grant g : requested) {
            String k = key(g.module(), g.action());
            Permission p = registered.get(k);
            if (p == null) throw new IllegalArgumentException("Unknown permission: " + k);
            // Only scopes the backend really evaluates for this permission (no DEPARTMENT/LOCATION, no TEAM
            // on an organisation-only function): a grant outside the catalogue range would silently do nothing.
            String problem = permissionCatalogue.problemWith(g.module(), g.action(), g.scope());
            if (problem != null) throw new IllegalArgumentException(problem);
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

    /** An authority string ("module:ACTION:SCOPE") on a node the catalogue marks HIGH sensitivity. */
    private boolean isHighImpact(String authority) {
        String module = authority.substring(0, authority.indexOf(':'));
        return permissionCatalogue.leaf(module).map(PermissionCatalogue.Node::high).orElse(false);
    }

    // ─── Membership ─────────────────────────────────────────────────────────────

    @Transactional
    public RoleDetail addMember(Integer roleId, UUID userId, String actorEmail) {
        if (isDefaultCategory(requireListedRole(roleId)))
            throw new IllegalArgumentException("Employee is the default category for people with no additional role — it can't be assigned.");
        Set<Integer> ids = currentRoleIds(userId);
        if (ids.add(roleId)) userManagementService.setUserRoles(userId, ids, actorEmail);
        return get(roleId);
    }

    /**
     * Adds or removes one role for several users. Deliberately NOT one transaction: each user goes
     * through the same guarded assignment path as a single change (privilege ceiling, no self-change,
     * last Super Admin…) in its own transaction, so a refused user is reported without blocking the rest.
     */
    public List<BulkMemberResult> bulkMembers(Integer roleId, List<UUID> userIds, boolean remove, String actorEmail) {
        Role role = requireListedRole(roleId);
        if (isDefaultCategory(role))
            throw new IllegalArgumentException("Employee is the default category for people with no additional role — it can't be assigned or removed.");
        List<UUID> distinct = userIds.stream().distinct().toList();
        if (distinct.isEmpty() || distinct.size() > 100)
            throw new IllegalArgumentException("Choose between 1 and 100 users.");
        Map<UUID, String> names = employeeRepository.findNamesByUserIds(new HashSet<>(distinct)).stream()
                .collect(Collectors.toMap(r -> (UUID) r[0], r -> (String) r[1], (a, b) -> a));
        List<BulkMemberResult> results = new ArrayList<>();
        for (UUID userId : distinct) {
            String name = names.getOrDefault(userId, userId.toString());
            try {
                Set<Integer> ids = currentRoleIds(userId);
                boolean changed = remove ? ids.remove(roleId) : ids.add(roleId);
                if (changed) userManagementService.setUserRoles(userId, ids, actorEmail);
                results.add(new BulkMemberResult(userId, name, true,
                        !changed ? (remove ? "Didn't hold this role" : "Already held this role") : (remove ? "Removed" : "Added")));
            } catch (RuntimeException e) {
                results.add(new BulkMemberResult(userId, name, false, e.getMessage() != null ? e.getMessage() : "Could not update this user (" + e.getClass().getSimpleName() + ")"));
            }
        }
        return results;
    }

    @Transactional
    public RoleDetail removeMember(Integer roleId, UUID userId, String actorEmail) {
        Role role = requireListedRole(roleId);
        if (isDefaultCategory(role))
            throw new IllegalArgumentException("Employee is the default category for people with no additional role — it can't be removed. Assign or remove other roles instead.");
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

    /** The built-in Employee entry: a derived, read-only category rather than an assignable role. */
    private static boolean isDefaultCategory(Role r) {
        return "EMPLOYEE".equals(r.getCode());
    }

    private static boolean isListed(Role r) {
        return !r.isSystem() || r.isPhase1();
    }

    private RoleSummary summary(Role r, long userCount, long permissionCount) {
        return new RoleSummary(r.getId(), r.getCode(), r.getDisplayName(), r.getDescription(), r.isSystem(), r.isActive(),
                !"SUPER_ADMIN".equals(r.getCode()) && !isDefaultCategory(r), !r.isSystem(), userCount, permissionCount, r.getCreatedAt(), r.getUpdatedAt(),
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

    /**
     * Tells every current holder's open sessions (once this transaction commits) that their
     * permissions changed, so nav/route guards update immediately without a re-login. Purely a UX
     * nudge: authorities are re-resolved on every request, so enforcement is already current.
     */
    private void notifyHoldersAfterCommit(Integer roleId) {
        List<UUID> holderIds = userRepository.findHoldersOfRole(roleId).stream().map(User::getId).toList();
        if (holderIds.isEmpty()) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    forceLogoutBroadcaster.permissionsChanged(holderIds);
                }
            });
            return;
        }
        forceLogoutBroadcaster.permissionsChanged(holderIds);
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
