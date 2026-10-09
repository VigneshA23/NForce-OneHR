package com.nforce.onehr.ai.navigation;

import com.nforce.onehr.ai.contract.PageReference;
import com.nforce.onehr.ai.contract.ShellRole;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The authoritative set of pages the assistant is allowed to talk about and navigate to, loaded
 * once at startup from {@code ai-knowledge/pages/registry.yaml}.
 *
 * <p>Loading is strict and fails fast: a malformed or missing registry throws at startup rather
 * than degrading into an empty registry at runtime. An empty registry would silently turn every
 * navigation answer into a non-navigating one, which looks like a model quality problem and would
 * be very hard to trace back to a YAML typo.
 *
 * <p>Uses snakeyaml, which Spring Boot already puts on the classpath for {@code application.yml} -
 * no new dependency.
 */
@Component
@Slf4j
public class PageRegistry {

    private static final String REGISTRY_PATH = "ai-knowledge/pages/registry.yaml";

    /** pageId to its variants, in declaration order. */
    private Map<String, List<PageReference>> byPageId = Map.of();

    @PostConstruct
    void load() {
        this.byPageId = parse(REGISTRY_PATH);
        log.info("Loaded AI page registry: {} pages, {} role variants",
                byPageId.size(),
                byPageId.values().stream().mapToInt(List::size).sum());
    }

    private Map<String, List<PageReference>> parse(String path) {
        Map<String, Object> root;
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            root = new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read AI page registry at " + path, e);
        }
        if (root == null || !(root.get("pages") instanceof List<?> pages)) {
            throw new IllegalStateException(path + " must contain a top-level 'pages' list");
        }

        Map<String, List<PageReference>> parsed = new LinkedHashMap<>();
        for (Object entry : pages) {
            if (!(entry instanceof Map<?, ?> page)) {
                throw new IllegalStateException("Each entry under 'pages' must be a mapping, got: " + entry);
            }
            String pageId = requireString(page.get("pageId"), "pageId", path);
            String module = requireString(page.get("module"), "module for page " + pageId, path);
            boolean placeholder = Boolean.TRUE.equals(page.get("placeholder"));

            if (!(page.get("variants") instanceof List<?> variants) || variants.isEmpty()) {
                throw new IllegalStateException("Page " + pageId + " must declare at least one variant in " + path);
            }

            List<PageReference> refs = new ArrayList<>();
            Set<ShellRole> seenRoles = EnumSet.noneOf(ShellRole.class);
            for (Object v : variants) {
                if (!(v instanceof Map<?, ?> variant)) {
                    throw new IllegalStateException("Variants of " + pageId + " must be mappings in " + path);
                }
                Set<ShellRole> roles = parseRoles(variant.get("roles"), pageId, path);

                // A role appearing in two variants of the same page would make label and
                // description resolution depend on declaration order, which is exactly the kind of
                // quiet ambiguity that produces a confidently wrong answer.
                for (ShellRole role : roles) {
                    if (!seenRoles.add(role)) {
                        throw new IllegalStateException(
                                "Page " + pageId + " declares role " + role + " in more than one variant in " + path);
                    }
                }

                refs.add(PageReference.builder()
                        .pageId(pageId)
                        .module(module)
                        .label(requireString(variant.get("label"), "label for page " + pageId, path))
                        .route(requireString(variant.get("route"), "route for page " + pageId, path))
                        .description(requireString(variant.get("description"), "description for page " + pageId, path))
                        .roles(roles)
                        .placeholder(placeholder)
                        .build());
            }

            if (parsed.putIfAbsent(pageId, List.copyOf(refs)) != null) {
                throw new IllegalStateException("Duplicate pageId " + pageId + " in " + path);
            }
        }
        return Map.copyOf(parsed);
    }

    private Set<ShellRole> parseRoles(Object raw, String pageId, String path) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("Page " + pageId + " has a variant with no roles in " + path);
        }
        Set<ShellRole> roles = EnumSet.noneOf(ShellRole.class);
        for (Object r : list) {
            roles.add(ShellRole.fromCode(String.valueOf(r))
                    .orElseThrow(() -> new IllegalStateException(
                            "Unknown shell role '" + r + "' on page " + pageId + " in " + path)));
        }
        return roles;
    }

    private String requireString(Object value, String what, String path) {
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("Missing or blank " + what + " in " + path);
        }
        return s;
    }

    /**
     * The variant of {@code pageId} this role actually sees, or empty when the page does not exist
     * or is not in that role's navigation.
     */
    public Optional<PageReference> find(String pageId, ShellRole role) {
        if (pageId == null || role == null) return Optional.empty();
        return byPageId.getOrDefault(pageId.trim(), List.of()).stream()
                .filter(ref -> ref.getRoles().contains(role))
                .findFirst();
    }

    /** True when the page exists at all, regardless of who can reach it. */
    public boolean exists(String pageId) {
        return pageId != null && byPageId.containsKey(pageId.trim());
    }

    /** Every variant visible to a role, for prompt context and knowledge validation. */
    public List<PageReference> forRole(ShellRole role) {
        if (role == null) return List.of();
        return byPageId.values().stream()
                .flatMap(Collection::stream)
                .filter(ref -> ref.getRoles().contains(role))
                .toList();
    }

    public Set<String> allPageIds() {
        return byPageId.keySet();
    }

    public int size() {
        return byPageId.size();
    }
}
