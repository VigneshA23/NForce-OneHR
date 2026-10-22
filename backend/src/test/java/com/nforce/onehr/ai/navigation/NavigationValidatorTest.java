package com.nforce.onehr.ai.navigation;

import com.nforce.onehr.ai.SeedAuthorities;
import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.NavigationAction;
import com.nforce.onehr.ai.contract.ShellRole;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.security.SeedMatrix;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Navigation is the one place the assistant can move a user somewhere they should not be, so these
 * cases are about refusal at least as much as success.
 *
 * <p>Runs against the real registry rather than a mock: the thing most worth protecting is the
 * actual shipped page list, and a mocked registry would only prove the validator agrees with
 * whatever the test invented.
 */
class NavigationValidatorTest {

    private static NavigationValidator validator;

    @BeforeAll
    static void setUp() {
        PageRegistry registry = new PageRegistry();
        ReflectionTestUtils.invokeMethod(registry, "load");
        validator = new NavigationValidator(registry);
    }

    private static AssistantRequestContext contextFor(ShellRole role, AudienceBucket... buckets) {
        return AssistantRequestContext.builder()
                .userId(UUID.randomUUID())
                .primaryRoleCode(role.name())
                .shellRole(role)
                .audiences(Set.of(buckets)).authorities(SeedAuthorities.of(Set.of(buckets)))
                .build();
    }

    @Nested
    @DisplayName("valid targets")
    class Accepts {

        @Test
        void employeeCanBeSentToTheirOwnPages() {
            Optional<NavigationAction> action =
                    validator.validate("leave", contextFor(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE));

            assertThat(action).isPresent();
            assertThat(action.get().getPageId()).isEqualTo("leave");
            assertThat(action.get().getLabel()).isEqualTo("Leave & Holidays");
        }

        @Test
        void superAdminCanBeSentToUserManagement() {
            Optional<NavigationAction> action = validator.validate("access",
                    contextFor(ShellRole.SUPER_ADMIN, AudienceBucket.ADMIN, AudienceBucket.EMPLOYEE));

            assertThat(action).isPresent();
            assertThat(action.get().getLabel()).isEqualTo("User Management");
        }

        @Test
        void surroundingWhitespaceFromTheModelIsTolerated() {
            assertThat(validator.validate("  leave  ", contextFor(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE)))
                    .isPresent();
        }

        @Test
        void managerCanBeSentToReports() {
            Optional<NavigationAction> action = validator.validate("reports",
                    contextFor(ShellRole.MANAGER, AudienceBucket.MANAGER, AudienceBucket.EMPLOYEE));

            assertThat(action).isPresent();
            assertThat(action.get().getLabel()).isEqualTo("Reports & Analytics");
        }

        @Test
        void hrAdminCanBeSentToReports() {
            Optional<NavigationAction> action = validator.validate("reports",
                    contextFor(ShellRole.HR_ADMIN, AudienceBucket.HR, AudienceBucket.EMPLOYEE));

            assertThat(action).isPresent();
            assertThat(action.get().getLabel()).isEqualTo("Reports & Analytics");
        }
    }

    @Nested
    @DisplayName("role-polymorphic routes resolve per role")
    class RolePolymorphic {

        @Test
        void requestsIsLabelledDifferentlyForEmployeeAndHrAdmin() {
            String employeeLabel = validator
                    .validate("requests", contextFor(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE))
                    .orElseThrow().getLabel();
            String hrLabel = validator
                    .validate("requests", contextFor(ShellRole.HR_ADMIN, AudienceBucket.HR, AudienceBucket.EMPLOYEE))
                    .orElseThrow().getLabel();

            assertThat(employeeLabel).isEqualTo("My Requests");
            assertThat(hrLabel).isEqualTo("HR Service Requests");
        }

        @Test
        void auditIsLabelledDifferentlyForManagerAndSuperAdmin() {
            String managerLabel = validator
                    .validate("audit", contextFor(ShellRole.MANAGER, AudienceBucket.MANAGER, AudienceBucket.EMPLOYEE))
                    .orElseThrow().getLabel();
            String superAdminLabel = validator
                    .validate("audit", contextFor(ShellRole.SUPER_ADMIN, AudienceBucket.ADMIN, AudienceBucket.EMPLOYEE))
                    .orElseThrow().getLabel();

            assertThat(managerLabel).isEqualTo("Audit History");
            assertThat(superAdminLabel).isEqualTo("Audit & Security");
        }

        @Test
        void aLabelSuppliedByTheModelIsDiscardedInFavourOfTheRegistry() {
            // The model does not get to name the destination - it could otherwise describe a page
            // as something it is not, which is most dangerous exactly where one route has two
            // legitimate names.
            NavigationAction action = validator
                    .validate("requests", contextFor(ShellRole.HR_ADMIN, AudienceBucket.HR))
                    .orElseThrow();

            assertThat(action.getLabel()).isEqualTo("HR Service Requests");
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refuses {

        @Test
        void unknownPageIdsAreDropped() {
            var employee = contextFor(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE);

            assertThat(validator.validate("payroll", employee)).isEmpty();
            assertThat(validator.validate("admin/settings", employee)).isEmpty();
            assertThat(validator.validate("https://example.invalid", employee)).isEmpty();
            assertThat(validator.validate("/leave", employee)).isEmpty();
        }

        @Test
        void aPageTheRoleCannotSeeIsDropped() {
            var employee = contextFor(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE);

            assertThat(validator.validate("access", employee)).isEmpty();
            assertThat(validator.validate("employees", employee)).isEmpty();
            assertThat(validator.validate("approvals", employee)).isEmpty();
            assertThat(validator.validate("masters", employee)).isEmpty();
        }

        /**
         * The specific bug that motivated {@link ShellRole}. A Super Admin holds the EMPLOYEE
         * audience bucket for real, so bucket-based authorisation would have offered them my-team,
         * which is in the Employee, Manager and HR Admin navs but not theirs.
         */
        @Test
        void superAdminIsNotOfferedMyTeamDespiteHoldingTheEmployeeBucket() {
            var superAdmin = contextFor(ShellRole.SUPER_ADMIN, AudienceBucket.ADMIN, AudienceBucket.EMPLOYEE);

            assertThat(superAdmin.getAudiences()).contains(AudienceBucket.EMPLOYEE);
            assertThat(validator.validate("my-team", superAdmin)).isEmpty();
        }

        @Test
        void superAdminIsNotOfferedTheExceptionDashboard() {
            // exceptions is Manager and HR Admin only, despite Super Admin outranking both.
            assertThat(validator.validate("exceptions",
                    contextFor(ShellRole.SUPER_ADMIN, AudienceBucket.ADMIN, AudienceBucket.EMPLOYEE)))
                    .isEmpty();
        }

        @Test
        void everyPhaseTwoPlaceholderIsRefusedForEveryRole() {
            // workflows shipped (Workflow Studio) and reports shipped (Reports & Analytics) — see
            // their real registry entries in ai-knowledge/pages/registry.yaml. Neither is a
            // placeholder any more.
            for (String placeholder : Set.of("performance", "integrations", "featurelab")) {
                for (ShellRole role : ShellRole.values()) {
                    assertThat(validator.validate(placeholder, contextFor(role, AudienceBucket.EMPLOYEE)))
                            .as("placeholder %s must never be a navigation target (role %s)", placeholder, role)
                            .isEmpty();
                }
            }
        }

        @Test
        void reportsIsRefusedForEmployee() {
            // The Reports & Analytics dashboard is Manager/HR/Super Admin only — nav.config.ts
            // has no 'reports' entry for Employee at all.
            assertThat(validator.validate("reports",
                    contextFor(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE)))
                    .isEmpty();
        }

        @Test
        void missingOrUnresolvableContextFailsClosed() {
            var noRole = AssistantRequestContext.builder()
                    .userId(UUID.randomUUID())
                    .audiences(Set.of(AudienceBucket.ADMIN)).authorities(SeedAuthorities.of(Set.of(AudienceBucket.ADMIN)))
                    .build();

            assertThat(validator.validate("leave", noRole)).isEmpty();
            assertThat(validator.validate("leave", null)).isEmpty();
            assertThat(validator.validate(null, contextFor(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE))).isEmpty();
            assertThat(validator.validate("", contextFor(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE))).isEmpty();
        }
    }

    @Nested
    @DisplayName("current-page hint")
    class CurrentPageHint {

        @Test
        void resolvesAPageTheUserCanActuallySee() {
            assertThat(validator.validateCurrentPage("attendance",
                    contextFor(ShellRole.MANAGER, AudienceBucket.MANAGER)))
                    .isPresent()
                    .get()
                    .extracting("label")
                    .isEqualTo("Team Attendance");
        }

        @Test
        void aSpoofedOrUnreachableHintIsDroppedRatherThanTrusted() {
            var employee = contextFor(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE);

            assertThat(validator.validateCurrentPage("access", employee)).isEmpty();
            assertThat(validator.validateCurrentPage("not-a-page", employee)).isEmpty();
        }

        @Test
        void aPlaceholderIsAcceptableAsAHintEvenThoughItIsNotATarget() {
            // Someone really can be sitting on the Phase 2 placeholder and ask what it is.
            var employee = contextFor(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE);

            assertThat(validator.validateCurrentPage("performance", employee)).isPresent();
            assertThat(validator.validate("performance", employee)).isEmpty();
        }
    }

    @Nested
    @DisplayName("permission-based reachability")
    class Permissions {

        private final PageRegistry registry = new PageRegistry();

        Permissions() {
            ReflectionTestUtils.invokeMethod(registry, "load");
        }

        /** An Employee whose custom role grants exactly {@code grants} ("module:ACTION" -> scope). */
        private AssistantRequestContext employeeWithCustomRole(Map<String, String> grants) {
            EffectivePermissionService permissions = SeedMatrix.permissionService(Map.of("CUSTOM_X", grants));
            Role custom = Role.builder().id(99).code("CUSTOM_X").displayName("Custom").build();
            return AssistantRequestContext.builder()
                    .userId(UUID.randomUUID()).primaryRoleCode("EMPLOYEE").shellRole(ShellRole.EMPLOYEE)
                    .audiences(Set.of(AudienceBucket.EMPLOYEE))
                    .authorities(permissions.coverage(List.of(SeedMatrix.role("EMPLOYEE"), custom)))
                    .customAuthorities(permissions.coverage(List.of(custom)))
                    .build();
        }

        @Test
        @DisplayName("with the seeded roles, every role (and real combination) reaches exactly its nav, as before")
        void systemRolesReachExactlyTheirOwnNav() {
            Map<ShellRole, String[]> holders = Map.of(
                    ShellRole.EMPLOYEE, new String[] {"EMPLOYEE"},
                    ShellRole.MANAGER, new String[] {"EMPLOYEE", "MANAGER"},
                    ShellRole.HR_ADMIN, new String[] {"EMPLOYEE", "MANAGER", "HR_ADMIN"},
                    ShellRole.SUPER_ADMIN, new String[] {"EMPLOYEE", "SUPER_ADMIN"});
            for (ShellRole role : ShellRole.values()) {
                for (String[] codes : List.of(holders.get(role), new String[] {"EMPLOYEE", role.name()})) {
                    var context = AssistantRequestContext.builder().shellRole(role)
                            .authorities(SeedAuthorities.ofRoles(Arrays.stream(codes).distinct().toArray(String[]::new)))
                            .customAuthorities(Set.of()).build();
                    assertThat(registry.reachable(context)).as("%s %s", role, Arrays.toString(codes))
                            .containsExactlyElementsOf(registry.forRole(role));
                }
            }
        }

        @Test
        @DisplayName("a custom role with user_management:VIEW:ALL can be navigated to User Management")
        void customRoleReachesItsGrantedPage() {
            var context = employeeWithCustomRole(Map.of("user_management:VIEW", "ALL"));

            Optional<NavigationAction> action = validator.validate("access", context);

            assertThat(action).isPresent();
            assertThat(action.get().getLabel()).isEqualTo("User Management");
            // Its own nav is untouched, and nothing it was not granted opens.
            assertThat(validator.validate("leave", context)).isPresent();
            assertThat(validator.validate("roles", context)).isEmpty();
            assertThat(validator.validate("employees", context)).isEmpty();
        }

        @Test
        @DisplayName("without the permission, a custom role reaches nothing beyond its primary role's nav")
        void customRoleWithoutThePermissionDoesNot() {
            var context = employeeWithCustomRole(Map.of("leave:VIEW", "ALL"));

            assertThat(validator.validate("access", context)).isEmpty();
            assertThat(registry.reachable(context)).containsExactlyElementsOf(registry.forRole(ShellRole.EMPLOYEE));
        }

        @Test
        @DisplayName("a custom-granted page uses the highest role's label, and same-requirement pages are offered once")
        void customGrantsMirrorTheSidebar() {
            var reports = employeeWithCustomRole(Map.of("reports:VIEW", "ALL", "organization:EDIT", "ALL"));

            assertThat(validator.labelFor("reports", reports)).contains("Reports & Analytics");
            // organization and masters share one requirement; the sidebar adds only the first.
            assertThat(validator.validate("organization", reports)).isPresent();
            assertThat(validator.validate("masters", reports)).isEmpty();
        }
    }
}
