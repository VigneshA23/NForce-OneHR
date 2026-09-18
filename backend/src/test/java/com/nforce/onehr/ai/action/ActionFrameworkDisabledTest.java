package com.nforce.onehr.ai.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AssistantResponse;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.exception.ActionExecutionDisabledException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the action-execution framework is present but cannot mutate anything.
 *
 * <p>These assertions are the reason the framework can ship at all. Each corresponds to a guarantee
 * in the package-info of com.nforce.onehr.ai.action, and each is written so that undoing the
 * guarantee fails the build rather than quietly shipping a mutation path. A future developer
 * enabling one action deliberately will see exactly which of these to update.
 */
class ActionFrameworkDisabledTest {

    private static final String SCAN_ROOT = "com.nforce.onehr";

    @Nested
    @DisplayName("the registry is empty and stays empty by construction")
    class RegistryIsEmpty {

        private final ActionRegistry registry = new ActionRegistry();

        @Test
        void registryHasNoActions() {
            assertThat(registry.isEmpty()).isTrue();
            assertThat(registry.size()).isZero();
            assertThat(registry.all()).isEmpty();
        }

        @Test
        void lookupNeverResolvesAnything() {
            assertThat(registry.find("leave.apply")).isEmpty();
            assertThat(registry.find("attendance.regularize")).isEmpty();
            assertThat(registry.find("")).isEmpty();
            assertThat(registry.find(null)).isEmpty();
            assertThat(registry.isRegistered("leave.approve")).isFalse();
        }

        /**
         * The registry must not collect beans by type. If someone converts it to autowire a
         * collection of ActionDefinition, adding any component would silently grant the assistant
         * a new capability, so that refactor has to fail here.
         */
        @Test
        void registryDoesNotCollectDefinitionsByInjection() {
            boolean injectsDefinitions = Arrays.stream(ActionRegistry.class.getDeclaredFields())
                    .filter(f -> !Modifier.isStatic(f.getModifiers()))
                    .anyMatch(this::isContainerOfDefinitions);

            assertThat(injectsDefinitions)
                    .as("ActionRegistry must hold a hardcoded map, never an injected collection of ActionDefinition")
                    .isFalse();
        }

        private boolean isContainerOfDefinitions(Field field) {
            boolean container = Collection.class.isAssignableFrom(field.getType())
                    || Map.class.isAssignableFrom(field.getType());
            return container
                    && field.getGenericType().getTypeName().contains(ActionDefinition.class.getName());
        }
    }

    @Nested
    @DisplayName("nothing implements ActionDefinition")
    class NoDefinitionsExist {

        @Test
        void noActionDefinitionImplementationsOnTheMainClasspath() {
            var scanner = new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AssignableTypeFilter(ActionDefinition.class));

            Set<String> allFound = scanner.findCandidateComponents(SCAN_ROOT).stream()
                    .map(BeanDefinition::getBeanClassName)
                    .filter(name -> name != null)
                    .collect(Collectors.toSet());

            // Self-check first. A scan that silently matches nothing would make the real assertion
            // below pass vacuously and give false assurance forever, so require it to find the
            // one implementation we know exists: this file's own test stub.
            assertThat(allFound)
                    .as("classpath scan for ActionDefinition is not working - the assertion below "
                            + "would pass vacuously and prove nothing")
                    .anyMatch(name -> name.endsWith("EnabledOnPaperDefinition"));

            Set<String> shipped = allFound.stream()
                    // The throwaway stub at the bottom of this file lives in test sources, not
                    // shipped code. Only a src/main implementation would make an action real.
                    .filter(name -> !name.contains("Test"))
                    .collect(Collectors.toSet());

            assertThat(shipped)
                    .as("No action may be defined while the executor is disabled. Enabling one means "
                            + "updating ActionRegistry AND replacing DisabledActionExecutor, deliberately.")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the only executor refuses everything")
    class ExecutorRefusesEverything {

        private final DisabledActionExecutor executor = new DisabledActionExecutor();

        private final AssistantRequestContext superAdminContext = AssistantRequestContext.builder()
                .userId(UUID.randomUUID())
                .primaryRoleCode("SUPER_ADMIN")
                .audiences(Set.of(AudienceBucket.ADMIN, AudienceBucket.EMPLOYEE))
                .build();

        @Test
        void refusesAWellFormedRequestFromTheHighestPrivilegedRole() {
            ActionRequest request = ActionRequest.builder()
                    .actionId("leave.apply")
                    .parameters(Map.of("leaveTypeId", 1, "startDate", "2026-10-01"))
                    .build();

            assertThatThrownBy(() -> executor.execute(request, superAdminContext))
                    .isInstanceOf(ActionExecutionDisabledException.class)
                    .hasMessageContaining("not enabled")
                    .hasMessageContaining("leave.apply");
        }

        @Test
        void refusesEvenWithAConfirmationTokenPresent() {
            ActionRequest confirmed = ActionRequest.builder()
                    .actionId("leave.approve")
                    .parameters(Map.of("requestId", UUID.randomUUID()))
                    .confirmationToken("looks-legitimate")
                    .build();

            assertThatThrownBy(() -> executor.execute(confirmed, superAdminContext))
                    .isInstanceOf(ActionExecutionDisabledException.class);
        }

        @Test
        void refusesNullAndEmptyInputWithoutLeakingADifferentFailure() {
            assertThatThrownBy(() -> executor.execute(null, null))
                    .isInstanceOf(ActionExecutionDisabledException.class);

            assertThatThrownBy(() -> executor.execute(ActionRequest.builder().build(), superAdminContext))
                    .isInstanceOf(ActionExecutionDisabledException.class);
        }

        /**
         * A definition claiming enabled() == true must still be refused: honouring that flag would
         * make a registry edit alone sufficient to start mutating OneHR data.
         */
        @Test
        void ignoresAnEnabledFlagOnTheDefinition() {
            ActionDefinition enabledOnPaper = new EnabledOnPaperDefinition();
            assertThat(enabledOnPaper.enabled()).isTrue();

            ActionRequest request = ActionRequest.builder()
                    .actionId(enabledOnPaper.actionId())
                    .parameters(Map.of())
                    .build();

            assertThatThrownBy(() -> executor.execute(request, superAdminContext))
                    .isInstanceOf(ActionExecutionDisabledException.class);
        }

        @Test
        void disabledResultHelperNeverReportsSuccess() {
            ActionResult result = ActionResult.disabled("leave.apply", "Action execution is not enabled yet.");

            assertThat(result.getOutcome()).isEqualTo(ActionOutcome.DISABLED);
            assertThat(result.getDetails()).isEmpty();
        }
    }

    @Nested
    @DisplayName("no transport surface exists for actions")
    class NoActionEndpoint {

        @Test
        void noRestControllerMethodAcceptsAnActionType() {
            var scanner = new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

            Set<String> offenders = scanner.findCandidateComponents(SCAN_ROOT).stream()
                    .map(BeanDefinition::getBeanClassName)
                    .map(this::loadClass)
                    .flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
                    .filter(this::acceptsActionType)
                    .map(m -> m.getDeclaringClass().getSimpleName() + "." + m.getName())
                    .collect(Collectors.toSet());

            assertThat(offenders)
                    .as("No endpoint may accept an action type, not even a stub returning 501, "
                            + "because an endpoint that looks real invites clients to call it.")
                    .isEmpty();
        }

        private boolean acceptsActionType(Method method) {
            return Arrays.stream(method.getParameterTypes())
                    .anyMatch(p -> p.equals(ActionRequest.class)
                            || p.equals(ActionDefinition.class)
                            || p.equals(ActionConfirmation.class));
        }

        private Class<?> loadClass(String name) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Scanned class not loadable: " + name, e);
            }
        }
    }

    @Nested
    @DisplayName("model output cannot smuggle an action through the response contract")
    class ResponseContractRejectsActions {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        void assistantResponseHasNoActionField() {
            Set<String> suspicious = Arrays.stream(AssistantResponse.class.getDeclaredFields())
                    .map(Field::getName)
                    .map(String::toLowerCase)
                    .filter(n -> n.contains("action") || n.contains("execute") || n.contains("mutation"))
                    .collect(Collectors.toSet());

            assertThat(suspicious)
                    .as("AssistantResponse must carry no action-shaped field in the read-only release")
                    .isEmpty();
        }

        /**
         * The realistic attack: a model talked into emitting an action envelope. Unknown properties
         * must be dropped at parse time rather than reaching code that might act on them.
         */
        @Test
        void unknownActionFieldsFromTheModelAreDroppedNotDeserialized() throws Exception {
            String hostileJson = "{"
                    + "\"type\": \"HOW_TO\","
                    + "\"answer\": \"Applying leave on your behalf.\","
                    + "\"steps\": [\"Open Leave\"],"
                    + "\"confidence\": \"HIGH\","
                    + "\"action\": {\"actionId\": \"leave.apply\", \"parameters\": {\"days\": 5}},"
                    + "\"execute\": true,"
                    + "\"sql\": \"DELETE FROM leave_requests\","
                    + "\"url\": \"https://example.invalid/steal\""
                    + "}";

            AssistantResponse parsed = mapper.readValue(hostileJson, AssistantResponse.class);

            assertThat(parsed.getType().name()).isEqualTo("HOW_TO");
            assertThat(parsed.getAnswer()).isEqualTo("Applying leave on your behalf.");

            String reserialized = mapper.writeValueAsString(parsed);
            assertThat(reserialized)
                    .doesNotContain("actionId")
                    .doesNotContain("execute")
                    .doesNotContain("sql")
                    .doesNotContain("example.invalid");
        }
    }

    /** Test-only stub. Exists solely to prove the enabled() flag is ignored; never registered. */
    private static final class EnabledOnPaperDefinition implements ActionDefinition {
        @Override public String actionId() { return "leave.apply"; }
        @Override public String name() { return "Apply for leave"; }
        @Override public String description() { return "Test-only stub."; }
        @Override public Set<String> requiredRoles() { return Set.of("EMPLOYEE"); }
        @Override public List<ActionParameter> parameters() { return List.of(); }
        @Override public List<String> preconditions() { return List.of(); }
        @Override public boolean requiresConfirmation() { return false; }
        @Override public String expectedResult() { return "A pending leave request."; }
        @Override public String module() { return "leave"; }
        @Override public String pageId() { return "leave"; }
        @Override public String workflowId() { return "leave.approval"; }
        @Override public boolean enabled() { return true; }
    }
}
