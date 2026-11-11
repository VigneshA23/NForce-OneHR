package com.nforce.onehr.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
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

    // ── Role-code literals: every remaining use must be reviewed and classified ──────────────────
    //
    // hasRole( is gone, but a service can still decide access with a bare comparison such as
    // "SUPER_ADMIN".equals(role.getCode()). Authorization must come from permissions
    // (module:ACTION:SCOPE) — a role's NAME must never grant anything. This test fails when a NEW
    // main-source file starts mentioning one of the built-in admin role codes as a string literal, so
    // the author has to decide: use a permission instead, or add the file below with its reason.
    //
    // Allowed categories (see the Roles & Permissions hardening plan):
    //   WORKFLOW — the role names a configured business stage/routing responsibility, not an access check
    //              (approval stages, payroll stages, "Assign To" lists, last-Super-Admin safeguards);
    //   DISPLAY  — labels, priority ordering, categorisation, audience targeting, AI/UI shell mapping.
    private static final Map<String, String> ALLOWED_ROLE_CODE_LITERALS = Map.ofEntries(
            Map.entry("util/RoleUtils.java", "DISPLAY: priority order, audience buckets"),
            Map.entry("util/RoleCategorization.java", "DISPLAY: role-list categorisation"),
            Map.entry("service/PayslipAccessPolicy.java", "AUTHORIZATION (reviewed exception, added by the payslip feature): owner or HR_ADMIN/SUPER_ADMIN by role code; "
                    + "migrate to an explicit payroll permission so custom roles can be granted payslip access"),
            Map.entry("ai/contract/ShellRole.java", "DISPLAY: AI/UI shell mapping"),
            Map.entry("ai/data/PeopleDataProviders.java", "DISPLAY: role labels"),
            Map.entry("dto/ApprovalItemDto.java", "WORKFLOW: approval stage tag"),
            Map.entry("dto/attendance/OvertimeRequestResponse.java", "DISPLAY: reviewer role label"),
            Map.entry("entity/PayrollRun.java", "WORKFLOW: payroll approval stage"),
            Map.entry("service/ApprovalRuleService.java", "WORKFLOW: Workflow Studio stage roles"),
            Map.entry("service/ApprovalRuleEvaluationService.java", "WORKFLOW: approval stage resolution"),
            Map.entry("service/PayrollRunService.java", "WORKFLOW: payroll stage (endpoint is permission-gated)"),
            Map.entry("service/ExpenseService.java", "WORKFLOW: expense approval stages + labels"),
            Map.entry("service/ApprovalCenterService.java", "WORKFLOW: expense stage tags"),
            Map.entry("service/OrgHierarchyService.java", "DISPLAY: org-chart role label"),
            Map.entry("service/UserManagementService.java", "WORKFLOW: last-Super-Admin safeguards, primary-role picker"),
            Map.entry("service/RoleAdminService.java", "WORKFLOW: protected Super Admin role (a restriction, never a bypass)"),
            Map.entry("service/LeaveService.java", "WORKFLOW: routing of a Super Admin's OWN leave"),
            Map.entry("service/RegularizationService.java", "DISPLAY: acting-role label, assignee-list exclusion"),
            Map.entry("service/AttendanceRequestService.java", "WORKFLOW: Super Admin not offered as 'Assign To'"),
            Map.entry("service/OvertimeRequestService.java", "WORKFLOW: Super Admin not offered as 'Assign To'"),
            Map.entry("service/EmployeeService.java", "WORKFLOW: reporting-hierarchy tier of a manager candidate"),
            Map.entry("service/PolicyService.java", "DISPLAY: policy audience targeting codes"),
            Map.entry("service/HelpContentService.java", "DISPLAY: help-content audience buckets"));

    private static final Pattern ROLE_CODE_LITERAL = Pattern.compile("\"(SUPER_ADMIN|HR_ADMIN|MANAGER)\"");

    @Test
    void roleCodeLiteralsAreOnlyUsedWhereReviewedAndClassified() throws IOException {
        TreeSet<String> unreviewed = new TreeSet<>();
        List<Path> javaFiles;
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        {
            for (Path path : javaFiles) {
                String source = Files.readString(path);
                source = BLOCK_COMMENT.matcher(source).replaceAll("");
                source = LINE_COMMENT.matcher(source).replaceAll("");
                if (!ROLE_CODE_LITERAL.matcher(source).find()) continue;
                String rel = path.toString().replace('\\', '/');
                rel = rel.substring(rel.indexOf("com/nforce/onehr/") + "com/nforce/onehr/".length());
                if (!ALLOWED_ROLE_CODE_LITERALS.containsKey(rel)) unreviewed.add(rel);
            }
        }
        assertTrue(unreviewed.isEmpty(),
                "A built-in role code literal (SUPER_ADMIN/HR_ADMIN/MANAGER) now appears in files not reviewed as WORKFLOW/DISPLAY. "
                        + "Authorize with a permission (hasAuthority/EffectivePermissionService) instead, or classify the file in "
                        + "ALLOWED_ROLE_CODE_LITERALS: " + unreviewed);
    }
}
