package com.nforce.onehr.ai.navigation;

import com.nforce.onehr.ai.contract.PageReference;
import com.nforce.onehr.ai.contract.ShellRole;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the AI page registry still matches the real sidebar in
 * {@code frontend/src/lib/nav.config.ts}.
 *
 * <p>This is the drift guard the whole navigation story rests on. The registry is a hand
 * transcription of a frontend TypeScript file that no backend code imports, so nothing else would
 * ever notice the two diverging. The failure mode is quiet and bad: someone adds, renames or
 * re-scopes a nav item, the registry keeps describing the old world, and the assistant starts
 * confidently offering a page a role cannot reach or naming it something the sidebar does not.
 *
 * <p>So this test parses nav.config.ts as text and compares it to the loaded registry. Parsing
 * another language with a regex is normally a poor idea; here it is the cheapest way to make a
 * cross-stack invariant enforceable, and it is deliberately brittle in the safe direction - if the
 * shape of nav.config.ts changes enough to break the parse, the self-check below fails loudly
 * rather than silently matching nothing.
 */
class PageRegistryParityTest {

    private static final Path NAV_CONFIG = Path.of("..", "frontend", "src", "lib", "nav.config.ts");

    /** {@code item('key', 'Label', Icon, phase)} - optionally followed by a locked flag. */
    private static final Pattern ITEM = Pattern.compile(
            "item\\(\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*,\\s*\\w+\\s*,\\s*(\\d)");

    private static PageRegistry registry;
    private static Map<ShellRole, Map<String, NavItem>> navByRole;

    private record NavItem(String key, String label, int phase) {}

    @BeforeAll
    static void setUp() throws IOException {
        registry = new PageRegistry();
        ReflectionTestUtils.invokeMethod(registry, "load");
        navByRole = parseNavConfig(Files.readString(NAV_CONFIG));
    }

    @Test
    void navConfigWasActuallyParsed() {
        // Guards against a vacuous pass: if the regex stopped matching, every comparison below
        // would trivially succeed against empty sets and prove nothing.
        assertThat(navByRole).hasSize(4);
        navByRole.forEach((role, items) -> assertThat(items)
                .as("no nav items parsed for %s - the nav.config.ts parser has broken", role)
                .isNotEmpty());
        assertThat(navByRole.get(ShellRole.EMPLOYEE)).containsKey("dashboard");
        assertThat(navByRole.get(ShellRole.SUPER_ADMIN)).containsKey("access");
    }

    @Test
    void everyNavItemHasARegistryEntryForThatRole() {
        Map<ShellRole, Set<String>> missing = new EnumMap<>(ShellRole.class);

        navByRole.forEach((role, items) -> {
            Set<String> absent = new LinkedHashSet<>();
            items.keySet().forEach(key -> {
                if (registry.find(key, role).isEmpty()) absent.add(key);
            });
            if (!absent.isEmpty()) missing.put(role, absent);
        });

        assertThat(missing)
                .as("nav.config.ts has items the AI page registry does not describe for that role. "
                        + "Add them to ai-knowledge/pages/registry.yaml.")
                .isEmpty();
    }

    @Test
    void everyRegistryEntryCorrespondsToARealNavItemForThatRole() {
        Map<ShellRole, Set<String>> phantom = new EnumMap<>(ShellRole.class);

        for (ShellRole role : ShellRole.values()) {
            Set<String> extra = new LinkedHashSet<>();
            for (PageReference ref : registry.forRole(role)) {
                // Pages explicitly flagged as non-nav (profile, notifications) are reached from the
                // top bar rather than the sidebar, so their absence from NAV is expected.
                if (isNonNavPage(ref.getPageId())) continue;
                if (!navByRole.get(role).containsKey(ref.getPageId())) extra.add(ref.getPageId());
            }
            if (!extra.isEmpty()) phantom.put(role, extra);
        }

        assertThat(phantom)
                .as("The AI page registry claims pages a role cannot actually see in nav.config.ts. "
                        + "The assistant would offer navigation the sidebar cannot honour.")
                .isEmpty();
    }

    @Test
    void registryLabelsMatchTheSidebarExactly() {
        Map<String, String> mismatches = new LinkedHashMap<>();

        for (ShellRole role : ShellRole.values()) {
            for (PageReference ref : registry.forRole(role)) {
                if (isNonNavPage(ref.getPageId())) continue;
                NavItem navItem = navByRole.get(role).get(ref.getPageId());
                if (navItem == null) continue; // covered by the phantom-page test above
                if (!navItem.label().equals(ref.getLabel())) {
                    mismatches.put(role + "/" + ref.getPageId(),
                            "registry=" + ref.getLabel() + " nav=" + navItem.label());
                }
            }
        }

        assertThat(mismatches)
                .as("Registry labels must match the sidebar verbatim, or the assistant will tell "
                        + "users to click something that is not called that. This matters most for "
                        + "the role-polymorphic pages (requests, audit) where one route legitimately "
                        + "has two different names.")
                .isEmpty();
    }

    @Test
    void placeholderFlagMatchesPhaseTwoInNavConfig() {
        Map<String, String> wrong = new LinkedHashMap<>();

        for (ShellRole role : ShellRole.values()) {
            for (PageReference ref : registry.forRole(role)) {
                if (isNonNavPage(ref.getPageId())) continue;
                NavItem navItem = navByRole.get(role).get(ref.getPageId());
                if (navItem == null) continue;
                boolean navSaysPlaceholder = navItem.phase() > 1;
                if (navSaysPlaceholder != ref.isPlaceholder()) {
                    wrong.put(role + "/" + ref.getPageId(),
                            "registry placeholder=" + ref.isPlaceholder() + " navPhase=" + navItem.phase());
                }
            }
        }

        assertThat(wrong)
                .as("A page shipped since the registry was written would still be refused as a "
                        + "navigation target, and a page rolled back to Phase 2 would be offered "
                        + "even though it renders a placeholder.")
                .isEmpty();
    }

    @Test
    void routesFollowTheNavConfigConvention() {
        // nav.config.ts derives every nav path as `/${key}`. Encoding that here means a registry
        // route typo cannot quietly point the docs at a path the sidebar never produces.
        for (ShellRole role : ShellRole.values()) {
            for (PageReference ref : registry.forRole(role)) {
                if (isNonNavPage(ref.getPageId())) continue;
                assertThat(ref.getRoute())
                        .as("route for %s", ref.getPageId())
                        .isEqualTo("/" + ref.getPageId());
            }
        }
    }

    private boolean isNonNavPage(String pageId) {
        return Set.of("profile", "notifications").contains(pageId);
    }

    /** Extracts {@code NAV} from nav.config.ts as role to (key to item). */
    private static Map<ShellRole, Map<String, NavItem>> parseNavConfig(String source) {
        // Isolate the NAV object literal so NAV_HIERARCHY, which also mentions these keys but
        // grants nothing, cannot contribute entries.
        int navStart = source.indexOf("export const NAV");
        int navEnd = source.indexOf("// ---", navStart);
        String nav = source.substring(navStart, navEnd > 0 ? navEnd : source.length());

        Map<ShellRole, String> blocks = new EnumMap<>(ShellRole.class);
        blocks.put(ShellRole.EMPLOYEE, sliceRole(nav, "Employee:"));
        blocks.put(ShellRole.MANAGER, sliceRole(nav, "Manager:"));
        blocks.put(ShellRole.HR_ADMIN, sliceRole(nav, "'HR Admin':"));
        blocks.put(ShellRole.SUPER_ADMIN, sliceRole(nav, "'Super Admin':"));

        Map<ShellRole, Map<String, NavItem>> parsed = new EnumMap<>(ShellRole.class);
        blocks.forEach((role, block) -> {
            Map<String, NavItem> items = new LinkedHashMap<>();
            Matcher m = ITEM.matcher(block);
            while (m.find()) {
                items.put(m.group(1), new NavItem(m.group(1), m.group(2), Integer.parseInt(m.group(3))));
            }
            parsed.put(role, items);
        });
        return parsed;
    }

    /** The text of one role's array, from its key up to the closing bracket of that array. */
    private static String sliceRole(String nav, String roleKey) {
        int start = nav.indexOf(roleKey);
        if (start < 0) throw new IllegalStateException("Role key not found in nav.config.ts: " + roleKey);
        int open = nav.indexOf('[', start);
        int close = nav.indexOf(']', open);
        return nav.substring(open, close);
    }
}
