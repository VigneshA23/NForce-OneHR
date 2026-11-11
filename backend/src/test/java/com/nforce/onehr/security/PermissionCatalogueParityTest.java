package com.nforce.onehr.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * permission-catalogue.yml is the definition; Flyway is what actually seeds the database. These tests
 * fail the build the moment the two disagree, and check that every authority string the backend
 * enforces is something the catalogue knows about (a typo there silently denies everyone).
 *
 * <p>Everything about the database is read from the migration SQL (the test profile does not run
 * Flyway), using the same tuple conventions the migrations are written in.
 */
class PermissionCatalogueParityTest {

    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");

    private static final Pattern HIERARCHY = Pattern.compile(
            "\\('([a-z_.]+)',\\s*'([a-z_.]*)',\\s*'(MODULE|SUBMODULE|SECTION)',\\s*(\\d+),\\s*'(NORMAL|HIGH)'\\)");
    private static final Pattern SCOPE_RANGE = Pattern.compile(
            "\\('([a-z_]+)',\\s*'([A-Z]+)',\\s*'(OWN|TEAM|ALL)',\\s*'(OWN|TEAM|ALL)'\\)");
    private static final Pattern AUTHORITY = Pattern.compile("\"([a-z_]+):([A-Z]+):(OWN|TEAM|DEPARTMENT|LOCATION|ALL)\"|'([a-z_]+):([A-Z]+):(OWN|TEAM|DEPARTMENT|LOCATION|ALL)'");

    private final PermissionCatalogue catalogue = PermissionCatalogue.loaded();

    // ── YAML <-> seeded permissions ────────────────────────────────────────────

    @Test
    void everyRegisteredPermission_isInTheCatalogue_andViceVersa() {
        Set<String> inYaml = new TreeSet<>();
        catalogue.leaves().forEach((code, n) -> n.actions().keySet().forEach(a -> inYaml.add(code + ":" + a)));
        Set<String> seeded = new TreeSet<>(SeedMatrix.registeredPermissions());

        Set<String> missingFromYaml = new TreeSet<>(seeded);
        missingFromYaml.removeAll(inYaml);
        Set<String> missingFromSql = new TreeSet<>(inYaml);
        missingFromSql.removeAll(seeded);
        assertTrue(missingFromYaml.isEmpty(), "Seeded by a migration but not in permission-catalogue.yml: " + missingFromYaml);
        assertTrue(missingFromSql.isEmpty(), "In permission-catalogue.yml but no migration seeds it: " + missingFromSql);
    }

    @Test
    void everySeededGrant_isWithinTheScopeRangeTheCatalogueAllows() {
        List<String> outside = new ArrayList<>();
        for (String role : List.of("MANAGER", "HR_ADMIN", "SUPER_ADMIN")) {
            SeedMatrix.grants(role).forEach((key, scope) -> {
                String[] ma = key.split(":");
                String problem = catalogue.problemWith(ma[0], ma[1], scope);
                if (problem != null) outside.add(role + " " + key + ":" + scope + " — " + problem);
            });
        }
        assertTrue(outside.isEmpty(), "Seeded default grants the catalogue would refuse:\n  " + String.join("\n  ", outside));
    }

    // ── YAML <-> seeded hierarchy and scope ranges ─────────────────────────────

    @Test
    void seededHierarchy_matchesTheCatalogue() {
        Map<String, String[]> sql = new HashMap<>();   // code -> {parent, type, sensitivity, sort}
        for (String body : migrationsInOrder()) {
            Matcher m = HIERARCHY.matcher(body);
            while (m.find()) sql.put(m.group(1), new String[]{m.group(2), m.group(3), m.group(5), m.group(4)});
        }
        Map<String, String[]> expected = new LinkedHashMap<>();   // code -> {parent, type, sensitivity}, from the YAML
        for (PermissionCatalogue.Node root : catalogue.modules()) flatten(root, "", expected);

        Set<String> diff = new TreeSet<>();
        expected.forEach((code, e) -> {
            String[] s = sql.get(code);
            if (s == null) diff.add(code + ": not seeded");
            else if (!Arrays.equals(Arrays.copyOf(e, 3), Arrays.copyOf(s, 3)))
                diff.add(code + ": yaml " + Arrays.toString(Arrays.copyOf(e, 3)) + " vs sql " + Arrays.toString(Arrays.copyOf(s, 3)));
        });
        sql.keySet().stream().filter(c -> !expected.containsKey(c)).forEach(c -> diff.add(c + ": seeded but not in the catalogue"));
        assertTrue(diff.isEmpty(), "Hierarchy drift between permission-catalogue.yml and the migrations:\n  " + String.join("\n  ", diff));
    }

    @Test
    void seededScopeRanges_matchTheCatalogue() {
        Map<String, String> sql = new TreeMap<>();
        for (String body : migrationsInOrder()) {
            Matcher m = SCOPE_RANGE.matcher(body);
            while (m.find()) sql.put(m.group(1) + ":" + m.group(2), m.group(3) + ".." + m.group(4));
        }
        Map<String, String> yaml = new TreeMap<>();
        catalogue.leaves().forEach((code, n) -> n.actions().forEach((a, r) -> yaml.put(code + ":" + a, r.min() + ".." + r.max())));
        assertEquals(yaml, sql, "Scope ranges differ between permission-catalogue.yml and the migrations");
    }

    // ── Catalogue shape ────────────────────────────────────────────────────────

    @Test
    void groupingNodesHoldNoGrants_andEveryLeafHasAtLeastOneAction() {
        for (PermissionCatalogue.Node root : catalogue.modules()) {
            assertEquals("MODULE", root.type());
            assertFalse(root.isLeaf(), root.code() + " is a module: grants live on leaves only");
            assertTrue(root.code().startsWith("mod."), root.code());
        }
        catalogue.leaves().values().forEach(n -> assertFalse(n.actions().isEmpty(), n.code()));
    }

    @Test
    void routeRequirements_areLoaded_andEveryRequirementIsAKnownPermission() {
        Map<String, List<String>> byPath = new HashMap<>();
        catalogue.routes().forEach(r -> byPath.put(r.path(), r.requires()));
        assertTrue(byPath.containsKey("/roles") && byPath.containsKey("/approvals") && byPath.containsKey("/access"));
        assertEquals(List.of("roles:VIEW:ALL"), byPath.get("/roles"));
        // PermissionCatalogue.load() already rejects an unknown authority; this pins the property explicitly.
        catalogue.routes().forEach(r -> r.requires().forEach(a -> {
            String[] ma = a.split(":");
            assertNull(catalogue.problemWith(ma[0], ma[1], ma[2]), r.path() + " requires " + a);
        }));
    }

    @Test
    void unsupportedScopesAreRefused() {
        assertNull(catalogue.problemWith("leave", "APPROVE", "TEAM"));
        assertNull(catalogue.problemWith("leave", "APPROVE", "ALL"));
        assertNotNull(catalogue.problemWith("leave", "APPROVE", "OWN"), "approving needs at least TEAM");
        assertNotNull(catalogue.problemWith("holidays", "VIEW", "TEAM"), "holidays are organisation-wide only");
        assertNotNull(catalogue.problemWith("leave", "VIEW", "DEPARTMENT"), "DEPARTMENT is not evaluated anywhere");
        assertNotNull(catalogue.problemWith("leave", "VIEW", "LOCATION"));
        assertNotNull(catalogue.problemWith("leave", "DELETE", "ALL"), "no such action on this node");
        assertNotNull(catalogue.problemWith("no_such_node", "VIEW", "ALL"));
        assertNull(catalogue.problemWith("audit", "VIEW", "OWN"));
        assertNotNull(catalogue.problemWith("audit", "VIEW", "ALL"), "audit is own-activity only");
    }

    // ── Every authority the backend enforces is known to the catalogue ─────────

    @Test
    void everyAuthorityLiteralInMainCode_isAKnownPermissionAtASupportedScope() throws IOException {
        List<String> unknown = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(f));
                Matcher m = AUTHORITY.matcher(code);
                while (m.find()) {
                    String module = m.group(1) != null ? m.group(1) : m.group(4);
                    String action = m.group(2) != null ? m.group(2) : m.group(5);
                    String scope = m.group(3) != null ? m.group(3) : m.group(6);
                    String problem = catalogue.problemWith(module, action, scope);
                    if (problem != null) unknown.add(f.getFileName() + ": " + module + ":" + action + ":" + scope + " — " + problem);
                }
            }
        }
        assertTrue(unknown.isEmpty(), "Authority strings the catalogue does not support:\n  " + String.join("\n  ", new TreeSet<>(unknown)));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static void flatten(PermissionCatalogue.Node n, String parent, Map<String, String[]> out) {
        // sort order is positional in the SQL (10, 20, ...) and not compared here: only parent/type/sensitivity are.
        out.put(n.code(), new String[]{parent, n.type(), n.high() ? "HIGH" : "NORMAL"});
        n.children().forEach(c -> flatten(c, n.code(), out));
    }

    private static List<String> migrationsInOrder() {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            return files.sorted(Comparator.comparingInt(PermissionCatalogueParityTest::version)).map(p -> {
                try {
                    return Files.readString(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int version(Path f) {
        Matcher m = Pattern.compile("^V(\\d+)(?:_(\\d+))?__").matcher(f.getFileName().toString());
        if (!m.find()) return Integer.MAX_VALUE;
        return Integer.parseInt(m.group(1)) * 1000 + (m.group(2) == null ? 0 : Integer.parseInt(m.group(2)));
    }

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
