package com.nforce.onehr.ai.navigation;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.NavigationAction;
import com.nforce.onehr.ai.contract.ShellRole;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
                .audiences(Set.of(buckets))
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
                    .audiences(Set.of(AudienceBucket.ADMIN))
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
}
