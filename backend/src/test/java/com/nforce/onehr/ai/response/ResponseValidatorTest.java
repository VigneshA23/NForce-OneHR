package com.nforce.onehr.ai.response;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AssistantResponse;
import com.nforce.onehr.ai.contract.AssistantResponseType;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.ConfidenceLevel;
import com.nforce.onehr.ai.contract.ShellRole;
import com.nforce.onehr.ai.navigation.NavigationValidator;
import com.nforce.onehr.ai.navigation.PageRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The validator is the only thing standing between raw model output and the browser, so these
 * cases are deliberately adversarial: malformed JSON, invented fields, wrong enums, unauthorised
 * navigation, and contradictory combinations.
 *
 * <p>Wired against the real registry and validator rather than mocks - the point is what the
 * shipped configuration actually permits.
 */
class ResponseValidatorTest {

    private static ResponseValidator validator;
    private static UnknownResponses unknownResponses;

    @BeforeAll
    static void setUp() {
        PageRegistry registry = new PageRegistry();
        ReflectionTestUtils.invokeMethod(registry, "load");
        NavigationValidator navigationValidator = new NavigationValidator(registry);
        unknownResponses = new UnknownResponses(navigationValidator);
        validator = new ResponseValidator(navigationValidator, unknownResponses);
    }

    private static AssistantRequestContext employee() {
        return context(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE);
    }

    private static AssistantRequestContext context(ShellRole role, AudienceBucket... buckets) {
        return AssistantRequestContext.builder()
                .userId(UUID.randomUUID())
                .primaryRoleCode(role.name())
                .shellRole(role)
                .audiences(Set.of(buckets))
                .build();
    }

    @Nested
    @DisplayName("well-formed output")
    class HappyPath {

        @Test
        void parsesACompleteHowToResponse() {
            String json = """
                    {"type":"HOW_TO","answer":"Here is how to apply for leave.",
                     "steps":["Open Leave & Holidays","Click Apply","Submit"],
                     "navigation":{"pageId":"leave"},
                     "related":[{"type":"WORKFLOW","refId":"workflow.leave.approval","label":"Leave approval"}],
                     "confidence":"HIGH"}
                    """;

            AssistantResponse r = validator.validate(json, employee());

            assertThat(r.getType()).isEqualTo(AssistantResponseType.HOW_TO);
            assertThat(r.getAnswer()).isEqualTo("Here is how to apply for leave.");
            assertThat(r.getSteps()).containsExactly("Open Leave & Holidays", "Click Apply", "Submit");
            assertThat(r.getNavigation().getPageId()).isEqualTo("leave");
            assertThat(r.getNavigation().getLabel()).isEqualTo("Leave & Holidays");
            assertThat(r.getRelated()).hasSize(1);
            assertThat(r.getConfidence()).isEqualTo(ConfidenceLevel.HIGH);
        }

        @Test
        void toleratesMarkdownFencesAndSurroundingProse() {
            String noisy = """
                    Sure, here is the answer you asked for:
                    ```json
                    {"type":"EXPLANATION","answer":"The Approval Center is one queue.","confidence":"HIGH"}
                    ```
                    Let me know if you need more.
                    """;

            AssistantResponse r = validator.validate(noisy, employee());

            assertThat(r.getType()).isEqualTo(AssistantResponseType.EXPLANATION);
            assertThat(r.getAnswer()).isEqualTo("The Approval Center is one queue.");
        }

        @Test
        void acceptsCaseVariantEnums() {
            AssistantResponse r = validator.validate(
                    "{\"type\":\"how_to\",\"answer\":\"Steps follow.\",\"confidence\":\"high\"}", employee());

            assertThat(r.getType()).isEqualTo(AssistantResponseType.HOW_TO);
            assertThat(r.getConfidence()).isEqualTo(ConfidenceLevel.HIGH);
        }
    }

    @Nested
    @DisplayName("malformed output degrades to a controlled decline")
    class Malformed {

        @Test
        void nullEmptyAndNonJsonAllDecline() {
            for (String bad : new String[]{null, "", "   ", "I am afraid I cannot help with that."}) {
                AssistantResponse r = validator.validate(bad, employee());
                assertThat(r).isNotNull();
                assertThat(r.getType()).isEqualTo(AssistantResponseType.UNKNOWN);
                assertThat(r.getAnswer()).isNotBlank();
            }
        }

        @Test
        void truncatedJsonDeclinesRatherThanThrowing() {
            AssistantResponse r = validator.validate(
                    "{\"type\":\"HOW_TO\",\"answer\":\"Here is how to ap", employee());

            assertThat(r.getType()).isEqualTo(AssistantResponseType.UNKNOWN);
        }

        @Test
        void validJsonWithNoAnswerTextDeclines() {
            // An empty bubble would read as a UI bug rather than a failure to answer.
            AssistantResponse r = validator.validate(
                    "{\"type\":\"HOW_TO\",\"steps\":[\"a\",\"b\"],\"confidence\":\"HIGH\"}", employee());

            assertThat(r.getType()).isEqualTo(AssistantResponseType.UNKNOWN);
            assertThat(r.getSteps()).isEmpty();
        }

        @Test
        void aDeclineAlwaysOffersTheHelpPage() {
            AssistantResponse r = validator.validate("not json", employee());

            assertThat(r.getNavigation()).isNotNull();
            assertThat(r.getNavigation().getPageId()).isEqualTo("help");
        }
    }

    @Nested
    @DisplayName("enum and field coercion")
    class Coercion {

        @Test
        void anUnrecognisedTypeBecomesExplanationAndKeepsTheAnswer() {
            AssistantResponse r = validator.validate(
                    "{\"type\":\"ACTION_PERFORMED\",\"answer\":\"Something informative.\",\"confidence\":\"HIGH\"}",
                    employee());

            assertThat(r.getType()).isEqualTo(AssistantResponseType.EXPLANATION);
            assertThat(r.getAnswer()).isEqualTo("Something informative.");
        }

        @Test
        void missingOrUnrecognisedConfidenceFailsToLowNotHigh() {
            // Defaulting upward would let a model that omitted the field look more certain than it
            // earned, and confidence is what a user leans on to decide whether to double-check.
            assertThat(validator.validate(
                    "{\"type\":\"EXPLANATION\",\"answer\":\"x\"}", employee()).getConfidence())
                    .isEqualTo(ConfidenceLevel.LOW);

            assertThat(validator.validate(
                    "{\"type\":\"EXPLANATION\",\"answer\":\"x\",\"confidence\":\"CERTAIN\"}", employee())
                    .getConfidence())
                    .isEqualTo(ConfidenceLevel.LOW);
        }

        @Test
        void unknownTypeIsAlwaysLowConfidenceAndCarriesNoSteps() {
            AssistantResponse r = validator.validate(
                    "{\"type\":\"UNKNOWN\",\"answer\":\"I am not sure.\","
                            + "\"steps\":[\"do this anyway\"],\"confidence\":\"HIGH\"}", employee());

            assertThat(r.getConfidence()).isEqualTo(ConfidenceLevel.LOW);
            assertThat(r.getSteps()).isEmpty();
        }

        @Test
        void blankStepsAreDroppedAndTheListIsCapped() {
            StringBuilder many = new StringBuilder();
            for (int i = 0; i < 40; i++) many.append("\"step ").append(i).append("\",");
            String json = "{\"type\":\"HOW_TO\",\"answer\":\"x\",\"steps\":[" + many + "\"\",\"   \"],"
                    + "\"confidence\":\"HIGH\"}";

            AssistantResponse r = validator.validate(json, employee());

            assertThat(r.getSteps()).hasSize(20);
            assertThat(r.getSteps()).noneMatch(String::isBlank);
        }
    }

    @Nested
    @DisplayName("navigation is re-derived, never trusted")
    class Navigation {

        @Test
        void anUnauthorisedPageIsStrippedAndNavigationDowngradedToExplanation() {
            AssistantResponse r = validator.validate(
                    "{\"type\":\"NAVIGATION\",\"answer\":\"Go to user management.\","
                            + "\"navigation\":{\"pageId\":\"access\"},\"confidence\":\"HIGH\"}", employee());

            assertThat(r.getNavigation()).isNull();
            assertThat(r.getType()).isEqualTo(AssistantResponseType.EXPLANATION);
            assertThat(r.getAnswer()).isNotBlank();
        }

        @Test
        void aninventedPageIsStripped() {
            AssistantResponse r = validator.validate(
                    "{\"type\":\"NAVIGATION\",\"answer\":\"Go to payroll.\","
                            + "\"navigation\":{\"pageId\":\"payroll\"},\"confidence\":\"HIGH\"}", employee());

            assertThat(r.getNavigation()).isNull();
        }

        @Test
        void aModelSuppliedLabelIsReplacedByTheRegistryLabel() {
            AssistantResponse r = validator.validate(
                    "{\"type\":\"NAVIGATION\",\"answer\":\"Here.\","
                            + "\"navigation\":{\"pageId\":\"requests\",\"label\":\"Payroll Portal\"},"
                            + "\"confidence\":\"HIGH\"}", employee());

            assertThat(r.getNavigation().getLabel()).isEqualTo("My Requests");
        }

        @Test
        void aValidNavigationOnANonNavigationTypeIsKept() {
            // A HOW_TO that also offers the page is good UX, not a contract violation.
            AssistantResponse r = validator.validate(
                    "{\"type\":\"HOW_TO\",\"answer\":\"Steps.\",\"steps\":[\"one\"],"
                            + "\"navigation\":{\"pageId\":\"leave\"},\"confidence\":\"HIGH\"}", employee());

            assertThat(r.getType()).isEqualTo(AssistantResponseType.HOW_TO);
            assertThat(r.getNavigation().getPageId()).isEqualTo("leave");
        }
    }

    @Nested
    @DisplayName("injected fields cannot survive validation")
    class InjectedFields {

        @Test
        void actionSqlAndUrlFieldsAreDiscarded() throws Exception {
            String hostile = """
                    {"type":"HOW_TO","answer":"Applying leave for you.","confidence":"HIGH",
                     "action":{"actionId":"leave.apply"},"execute":true,
                     "sql":"DELETE FROM leave_requests","url":"https://example.invalid",
                     "conversationId":"forged-conversation"}
                    """;

            AssistantResponse r = validator.validate(hostile, employee());
            String serialized = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(r);

            assertThat(serialized)
                    .doesNotContain("actionId")
                    .doesNotContain("execute")
                    .doesNotContain("sql")
                    .doesNotContain("example.invalid");
        }

        @Test
        void aModelSuppliedConversationIdIsNotCarriedThrough() {
            // conversationId is server-assigned; accepting one from the model would let it point a
            // turn at someone else's conversation.
            AssistantResponse r = validator.validate(
                    "{\"type\":\"EXPLANATION\",\"answer\":\"x\",\"conversationId\":\"forged\","
                            + "\"confidence\":\"HIGH\"}", employee());

            assertThat(r.getConversationId()).isNull();
        }
    }

    @Nested
    @DisplayName("controlled declines")
    class Declines {

        @Test
        void everyDeclineIsAUsableResponseWithText() {
            var ctx = employee();
            for (AssistantResponse r : new AssistantResponse[]{
                    unknownResponses.notEnoughKnowledge(ctx),
                    unknownResponses.outOfScope(ctx),
                    unknownResponses.providerUnavailable(ctx),
                    unknownResponses.malformedResponse(ctx),
                    unknownResponses.rateLimited(ctx),
                    unknownResponses.messageTooLong(ctx, 1000)}) {
                assertThat(r.getType()).isEqualTo(AssistantResponseType.UNKNOWN);
                assertThat(r.getAnswer()).isNotBlank();
                assertThat(r.getConfidence()).isEqualTo(ConfidenceLevel.LOW);
                assertThat(UnknownResponses.isDecline(r)).isTrue();
            }
        }

        @Test
        void providerFailureNeverNamesTheVendorOrLeaksAnError() {
            String answer = unknownResponses.providerUnavailable(employee()).getAnswer().toLowerCase();

            assertThat(answer)
                    .doesNotContain("mistral")
                    .doesNotContain("exception")
                    .doesNotContain("500")
                    .doesNotContain("api");
        }

        @Test
        void askingTheAssistantToActExplainsInsteadOfRefusingFlatly() {
            AssistantResponse r = unknownResponses.actionNotSupported(employee(), "leave");

            assertThat(r.getType()).isEqualTo(AssistantResponseType.EXPLANATION);
            assertThat(r.getAnswer()).containsIgnoringCase("read-only");
            assertThat(r.getNavigation().getPageId()).isEqualTo("leave");
        }

        @Test
        void actionNotSupportedStillRefusesAnUnauthorisedDestination() {
            AssistantResponse r = unknownResponses.actionNotSupported(employee(), "access");

            assertThat(r.getNavigation()).isNull();
        }
    }
}
