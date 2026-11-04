package com.nforce.onehr.security;

import com.nforce.onehr.entity.Permission;
import com.nforce.onehr.entity.PermissionModule;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.RolePermission;
import com.nforce.onehr.repository.RolePermissionRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.mockito.Mockito.mock;

/**
 * The REAL default permission matrix, parsed straight from the Flyway migrations (every
 * {@code ('ROLE', 'module', 'ACTION', 'SCOPE')} seed tuple, plus every registered
 * {@code ('module', 'ACTION')} pair), so service tests can run the real EffectivePermissionService
 * against exactly what production seeds — "HR Admin keeps the same behavior after a role-code check
 * becomes a permission check" is then proven against the seed, not a hand-copied stub.
 *
 * <p>SUPER_ADMIN also gets ALL for every registered pair it has no explicit tuple for — mirroring
 * V233's backfill + trigger, which guarantee Super Admin holds every registered permission.
 *
 * <p>Usage: {@code @Spy EffectivePermissionService eps = SeedMatrix.permissionService();} and build
 * roles with {@link #role(String)} (ids/system flags match V1's seed order).
 */
public final class SeedMatrix {

    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");
    private static final Pattern GRANT = Pattern.compile(
            "\\('(EMPLOYEE|MANAGER|HR_ADMIN|SUPER_ADMIN)',\\s*'([a-z_]+)',\\s*'([A-Z]+)',\\s*'(OWN|TEAM|DEPARTMENT|LOCATION|ALL)'\\)");
    private static final Pattern PERMISSION = Pattern.compile(
            "\\('([a-z_]+)',\\s*'(VIEW|CREATE|EDIT|DELETE|APPROVE|REVERT|EXPORT|CONFIGURE)'\\)");
    private static final List<String> SYSTEM = List.of("EMPLOYEE", "MANAGER", "HR_ADMIN", "SUPER_ADMIN");

    /** role code -> "module:ACTION" -> scope */
    private static final Map<String, Map<String, String>> MATRIX = load();

    private SeedMatrix() {}

    /** A system role exactly as seeded (EMPLOYEE=1, MANAGER=2, HR_ADMIN=3, SUPER_ADMIN=4). */
    public static Role role(String code) {
        int i = SYSTEM.indexOf(code);
        if (i < 0) throw new IllegalArgumentException("Not a seeded system role: " + code);
        return Role.builder().id(i + 1).code(code).displayName(code).system(true).phase1(true).build();
    }

    /** The seeded grants of one system role, as "module:ACTION" -> scope. */
    public static Map<String, String> grants(String code) {
        return Collections.unmodifiableMap(MATRIX.getOrDefault(code, Map.of()));
    }

    /**
     * A real EffectivePermissionService whose grants come from the seed (system roles, by code) plus
     * {@code extra} grants for any other role code (e.g. a custom role in a test). Repository-backed
     * methods other than grant lookup aren't available.
     */
    public static EffectivePermissionService permissionService(Map<String, Map<String, String>> extra) {
        Map<String, Map<String, String>> all = new HashMap<>(MATRIX);
        all.putAll(extra);
        return new EffectivePermissionService(mock(RolePermissionRepository.class)) {
            @Override
            protected List<RolePermission> grantsFor(Collection<Role> roles) {
                List<RolePermission> rows = new ArrayList<>();
                for (Role r : activeRoles(roles)) {
                    all.getOrDefault(r.getCode(), Map.of()).forEach((key, scope) -> {
                        String[] ma = key.split(":");
                        PermissionModule m = PermissionModule.builder().code(ma[0]).displayName(ma[0]).build();
                        rows.add(RolePermission.builder().role(r)
                                .permission(Permission.builder().module(m).action(ma[1]).build()).scope(scope).build());
                    });
                }
                return rows;
            }
        };
    }

    public static EffectivePermissionService permissionService() {
        return permissionService(Map.of());
    }

    private static Map<String, Map<String, String>> load() {
        Map<String, Map<String, String>> matrix = new HashMap<>();
        Set<String> registered = new TreeSet<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path f : files.sorted(Comparator.comparingInt(SeedMatrix::version)).toList()) {
                String sql = Files.readString(f);
                Matcher g = GRANT.matcher(sql);
                while (g.find()) {
                    String key = g.group(2) + ":" + g.group(3);
                    matrix.computeIfAbsent(g.group(1), k -> new HashMap<>()).put(key, g.group(4));
                    registered.add(key);
                }
                Matcher p = PERMISSION.matcher(sql);
                while (p.find()) registered.add(p.group(1) + ":" + p.group(2));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> sa = matrix.computeIfAbsent("SUPER_ADMIN", k -> new HashMap<>());
        for (String key : registered) sa.putIfAbsent(key, "ALL");
        return matrix;
    }

    private static int version(Path f) {
        Matcher m = Pattern.compile("^V(\\d+)__").matcher(f.getFileName().toString());
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }
}
