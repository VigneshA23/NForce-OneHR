package com.nforce.onehr.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every HTTP endpoint must either carry a {@code @PreAuthorize} (method or class) or be listed in
 * {@code src/test/resources/endpoint-gating-allowlist.txt} with the reason it is allowed to be
 * authenticated-only (self-service by ownership, gated inside the service, or intentionally public
 * to every signed-in user).
 *
 * <p>This is the guard that would have caught an endpoint shipped with no permission check at all
 * (PUT /api/projects/{id} was one): a new un-annotated endpoint fails the build until someone writes
 * down why it is safe. It is also a ratchet — an allow-list entry whose endpoint is now annotated, or
 * no longer exists, fails too, so the list only ever shrinks or stays honest.
 *
 * <p>Allow-list format, one per line: {@code ControllerSimpleName#method | reason}. Reasons start with
 * SELF (acts only on the caller's own data), SERVICE (permission/eligibility enforced in the service),
 * or OPEN (deliberately readable by every signed-in user). Blank lines and {@code #} comments allowed.
 */
class EveryEndpointGatedTest {

    private static final Path ALLOW_LIST = Path.of("src", "test", "resources", "endpoint-gating-allowlist.txt");

    @Test
    void everyEndpointIsGatedOrExplicitlyAllowListed() {
        Set<String> ungated = ungatedEndpoints();
        Set<String> allowed = allowList().keySet();

        Set<String> unlisted = new TreeSet<>(ungated);
        unlisted.removeAll(allowed);
        assertTrue(unlisted.isEmpty(),
                "Endpoints with no @PreAuthorize and no allow-list entry (add a permission gate, or list them in "
                        + ALLOW_LIST + " with a SELF/SERVICE/OPEN reason):\n  " + String.join("\n  ", unlisted));

        Set<String> stale = new TreeSet<>(allowed);
        stale.removeAll(ungated);
        assertTrue(stale.isEmpty(),
                "Allow-list entries that are now gated or no longer exist (remove them):\n  " + String.join("\n  ", stale));
    }

    @Test
    void everyAllowListEntryStatesItsReason() {
        allowList().forEach((endpoint, reason) -> assertTrue(
                reason.startsWith("SELF") || reason.startsWith("SERVICE") || reason.startsWith("OPEN"),
                endpoint + " needs a SELF / SERVICE / OPEN reason, was: '" + reason + "'"));
    }

    private static Set<String> ungatedEndpoints() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> out = new TreeSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.nforce.onehr")) {
            Class<?> controller;
            try {
                controller = Class.forName(bd.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            boolean classGated = controller.isAnnotationPresent(PreAuthorize.class);
            for (Method m : controller.getDeclaredMethods()) {
                if (AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class) == null) continue;
                if (classGated || m.isAnnotationPresent(PreAuthorize.class)) continue;
                out.add(controller.getSimpleName() + "#" + m.getName());
            }
        }
        return out;
    }

    private static Map<String, String> allowList() {
        Map<String, String> map = new TreeMap<>();
        try {
            for (String raw : Files.readAllLines(ALLOW_LIST)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int bar = line.indexOf('|');
                String key = (bar < 0 ? line : line.substring(0, bar)).strip();
                String reason = bar < 0 ? "" : line.substring(bar + 1).strip();
                map.put(key, reason);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return map;
    }
}
