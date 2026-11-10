package com.nforce.onehr.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Permission-system Phase 6 acceptance criterion: "no hasRole( remains in controllers/services,
 * enforced by a test". Scans every main-source .java file as plain text (comments/string literals
 * stripped first) for the Spring Security SpEL calls {@code hasRole(}/{@code hasAnyRole(} — every
 * real authorization gate was migrated to {@code hasAuthority(module:ACTION:SCOPE)} against the
 * role_permissions matrix in Phase 3 (see V225's seed data).
 *
 * <p>Deliberately NOT scoped to just the controller/service packages the acceptance criterion
 * names — scanning all of src/main/java catches a check added anywhere (e.g. a nested class, a
 * config file) that the narrower scope would miss, and costs nothing extra since there's nothing
 * legitimate left to exclude.
 *
 * <p>Two call sites are internal business-logic helpers that happen to share "hasRole" as a
 * substring of a longer, differently-capitalized identifier — {@code actorHasRole}/
 * {@code employeeHasRole} in RegularizationService/ExpenseService/LeaveService (approval-routing
 * and audit-labeling logic, never a security gate — see their own class comments). A
 * case-sensitive search for the literal, lowercase-leading {@code hasRole(}/{@code hasAnyRole(}
 * does not match those (the embedded occurrence is capitalized, e.g. "...HasRole("), so no
 * explicit exclusion list is needed for them. {@code OpportunityService}'s own helper was renamed
 * to {@code actorHasAnyRole} for the same reason rather than added to the exclusion below.
 *
 * <p>There are no exceptions: the Bench Management / Compensation / Project / Opportunity / Skill
 * controllers that were once exempt here were migrated onto their own permission modules (V241).
 */
class NoRawRoleChecksTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");



    // Matches the literal SpEL call, not an occurrence embedded in a longer camelCase identifier
    // (a non-word-character or start-of-string must precede "hasRole"/"hasAnyRole").
    private static final Pattern RAW_ROLE_CHECK = Pattern.compile("(?<![A-Za-z0-9_])(hasRole|hasAnyRole)\\(");

    private static final Pattern LINE_COMMENT = Pattern.compile("//.*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    @Test
    void noControllerOrServiceUsesRawRoleNameChecks() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .forEach(path -> {
                        String source;
                        try {
                            source = Files.readString(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        String withoutComments = BLOCK_COMMENT.matcher(source).replaceAll("");
                        withoutComments = LINE_COMMENT.matcher(withoutComments).replaceAll("");
                        Matcher m = RAW_ROLE_CHECK.matcher(withoutComments);
                        if (m.find()) {
                            offenders.add(path.toString());
                        }
                    });
        }
        assertTrue(offenders.isEmpty(),
                "hasRole(/hasAnyRole( SpEL checks remain (replace with hasAuthority(module:ACTION:SCOPE)): "
                        + offenders);
    }
}
