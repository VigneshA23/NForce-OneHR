package com.nforce.onehr.security;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The central permission catalogue, loaded once at startup from {@code permission-catalogue.yml}.
 *
 * <p>It is the authoritative definition of the Module > Submodule > Section hierarchy and of which
 * actions and scopes each permission node supports. The engine itself is unchanged: a grant is still
 * {@code <node>:<ACTION>:<SCOPE>} on a leaf node, parents hold nothing, so there is no inheritance —
 * access to a module never implies access to a submodule. This class only describes and validates.
 *
 * <p>Loading is strict and fails fast (same convention as the AI PageRegistry): a malformed catalogue
 * stops the application at startup instead of silently degrading into "nothing is grantable".
 */
@Component
public class PermissionCatalogue {

    public static final String RESOURCE = "permission-catalogue.yml";

    /** Scopes the backend can evaluate, narrowest first. DEPARTMENT/LOCATION are deliberately not here. */
    public static final List<String> SCOPE_ORDER = List.of("OWN", "TEAM", "ALL");

    /** Canonical display order of actions. */
    public static final List<String> ACTION_ORDER =
            List.of("VIEW", "CREATE", "EDIT", "DELETE", "APPROVE", "EXPORT", "CONFIGURE");

    /** Lowest and highest scope an action supports; {@code allows} is range membership. */
    public record ScopeRange(String min, String max) {
        public boolean allows(String scope) {
            int s = SCOPE_ORDER.indexOf(scope);
            return s >= 0 && s >= SCOPE_ORDER.indexOf(min) && s <= SCOPE_ORDER.indexOf(max);
        }
    }

    /** type is MODULE (depth 1), SUBMODULE (2) or SECTION (3); actions is empty for a pure container. */
    public record Node(String code, String name, String description, String type, boolean high,
                       Map<String, ScopeRange> actions, List<Node> children) {
        public boolean isLeaf() {
            return !actions.isEmpty();
        }
    }

    /** A page that needs a permission: open when the user holds at least one of {@code requires}. */
    public record Route(String path, List<String> requires) {}

    /** What {@link #parse} returns: the module tree plus the route requirements. */
    record Parsed(List<Node> modules, List<Route> routes) {}

    private List<Node> modules = List.of();
    private List<Route> routes = List.of();
    private Map<String, Node> leaves = Map.of();

    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            Parsed p = parse(in);
            apply(p.modules());
            this.routes = List.copyOf(p.routes());
            for (Route r : routes)
                for (String a : r.requires()) {
                    String[] ma = a.split(":");
                    String problem = ma.length == 3 ? problemWith(ma[0], ma[1], ma[2]) : "malformed authority";
                    if (problem != null) throw new IllegalStateException(RESOURCE + ": route " + r.path() + " requires " + a + " — " + problem);
                }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
    }

    /** The catalogue exactly as production loads it, for tests that need real scope ranges. */
    public static PermissionCatalogue loaded() {
        PermissionCatalogue c = new PermissionCatalogue();
        c.load();
        return c;
    }

    /** Test seam / reload: replaces the in-memory catalogue. */
    void apply(List<Node> roots) {
        Map<String, Node> byCode = new LinkedHashMap<>();
        for (Node root : roots) index(root, byCode);
        this.modules = List.copyOf(roots);
        this.leaves = Collections.unmodifiableMap(byCode);
    }

    private static void index(Node n, Map<String, Node> leaves) {
        if (n.isLeaf() && leaves.put(n.code(), n) != null)
            throw new IllegalStateException("Duplicate permission node in " + RESOURCE + ": " + n.code());
        n.children().forEach(c -> index(c, leaves));
    }

    // ── Reads ────────────────────────────────────────────────────────────────────

    /** The top-level modules, each with its nested children, in display order. */
    public List<Node> modules() {
        return modules;
    }

    /** The pages that need a permission; every other page is baseline self-service. */
    public List<Route> routes() {
        return routes;
    }

    /** Every node that can hold grants, by code, in catalogue order. */
    public Map<String, Node> leaves() {
        return leaves;
    }

    public Optional<Node> leaf(String code) {
        return Optional.ofNullable(leaves.get(code));
    }

    public Optional<ScopeRange> range(String code, String action) {
        return leaf(code).map(n -> n.actions().get(action));
    }

    /** null when {@code module:ACTION:scope} is a supported grant, otherwise the reason it is not. */
    public String problemWith(String module, String action, String scope) {
        Node leaf = leaves.get(module);
        if (leaf == null) return "Unknown permission: " + module + ":" + action;
        ScopeRange r = leaf.actions().get(action);
        if (r == null) return "Unknown permission: " + module + ":" + action;
        if (!SCOPE_ORDER.contains(scope)) return "Invalid scope '" + scope + "' for " + module + ":" + action;
        if (!r.allows(scope)) return "'" + scope + "' is not supported for " + leaf.name() + " (" + action + "): "
                + (r.min().equals(r.max()) ? "only " + r.max() : r.min() + " to " + r.max()) + ".";
        return null;
    }

    // ── Parsing ──────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static Parsed parse(InputStream in) {
        Object root = new Yaml().load(in);
        if (!(root instanceof Map<?, ?> m) || !(m.get("modules") instanceof List<?> list) || list.isEmpty())
            throw new IllegalStateException(RESOURCE + " must have a non-empty 'modules' list");
        List<Node> out = new ArrayList<>();
        for (Object o : list) out.add(node((Map<String, Object>) o, 1));
        List<Route> routes = new ArrayList<>();
        if (m.get("routes") instanceof List<?> rl) {
            for (Object o : rl) {
                Map<String, Object> rm = (Map<String, Object>) o;
                List<String> requires = new ArrayList<>();
                if (rm.get("requires") instanceof List<?> req) req.forEach(x -> requires.add(String.valueOf(x)));
                if (requires.isEmpty()) throw new IllegalStateException(RESOURCE + ": route " + rm.get("path") + " has no 'requires'");
                routes.add(new Route(str(rm, "path"), List.copyOf(requires)));
            }
        }
        return new Parsed(out, routes);
    }

    @SuppressWarnings("unchecked")
    private static Node node(Map<String, Object> m, int depth) {
        String code = str(m, "code");
        if (depth > 3) throw new IllegalStateException(RESOURCE + ": " + code + " is nested deeper than Module > Submodule > Section");
        Map<String, ScopeRange> actions = new LinkedHashMap<>();
        Object a = m.get("actions");
        if (a instanceof Map<?, ?> am) {
            List<String> keys = new ArrayList<>();
            am.keySet().forEach(k -> keys.add(String.valueOf(k)));
            keys.sort((x, y) -> Integer.compare(ACTION_ORDER.indexOf(x), ACTION_ORDER.indexOf(y)));
            for (String action : keys) {
                if (!ACTION_ORDER.contains(action)) throw new IllegalStateException(RESOURCE + ": " + code + " has unknown action " + action);
                actions.put(action, range(code, action, String.valueOf(am.get(action))));
            }
        }
        List<Node> children = new ArrayList<>();
        if (m.get("children") instanceof List<?> cl) for (Object c : cl) children.add(node((Map<String, Object>) c, depth + 1));
        if (!actions.isEmpty() && !children.isEmpty())
            throw new IllegalStateException(RESOURCE + ": " + code + " has both actions and children — grants live on leaves only");
        if (actions.isEmpty() && children.isEmpty())
            throw new IllegalStateException(RESOURCE + ": " + code + " has neither actions nor children");
        String type = depth == 1 ? "MODULE" : depth == 2 ? "SUBMODULE" : "SECTION";
        return new Node(code, str(m, "name"), m.get("description") == null ? "" : String.valueOf(m.get("description")).strip(),
                type, "HIGH".equals(m.get("sensitivity")), actions, List.copyOf(children));
    }

    private static ScopeRange range(String code, String action, String text) {
        String[] parts = text.trim().split("\\.\\.");
        String min = parts[0].trim();
        String max = (parts.length > 1 ? parts[1] : parts[0]).trim();
        if (!SCOPE_ORDER.contains(min) || !SCOPE_ORDER.contains(max) || SCOPE_ORDER.indexOf(min) > SCOPE_ORDER.indexOf(max))
            throw new IllegalStateException(RESOURCE + ": " + code + ":" + action + " has an invalid scope range '" + text + "'");
        return new ScopeRange(min, max);
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || String.valueOf(v).isBlank()) throw new IllegalStateException(RESOURCE + ": missing '" + key + "' in " + m);
        return String.valueOf(v).trim();
    }
}
