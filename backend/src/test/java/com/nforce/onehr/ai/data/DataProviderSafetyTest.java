package com.nforce.onehr.ai.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guards on the live-data layer.
 *
 * <p>This is the part of the assistant that reads real employee records and puts them in a prompt
 * that goes to a third party. The behavioural tests cover what it does; these cover what it must
 * never become, because the dangerous version of this feature looks almost identical to the safe
 * one and would pass every functional test.
 */
class DataProviderSafetyTest {

    private static final String SCAN_ROOT = "com.nforce.onehr.ai.data";

    /**
     * Service methods that return data for somebody other than the caller, or for everyone.
     *
     * <p>{@code listOrgLeave} takes no actor at all. A provider reaching one of these would hand
     * the model an entire organisation's leave, and it would look like an ordinary one-line change.
     */
    private static final Set<String> FORBIDDEN_CALLS = Set.of(
            "listorgleave", "listallassets", "listallholidays",
            "listteamleave", "findall", "listall");

    /** Anything that writes. A provider is a read; this is what keeps it one. */
    private static final Set<String> MUTATING_PREFIXES = Set.of(
            "submit", "create", "update", "delete", "approve", "reject", "cancel",
            "save", "initialize", "checkin", "checkout", "clear", "publish");

    private List<Class<?>> providerClasses() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(AssistantDataProvider.class));
        return scanner.findCandidateComponents(SCAN_ROOT).stream()
                .map(BeanDefinition::getBeanClassName)
                .map(name -> {
                    try { return Class.forName(name); }
                    catch (ClassNotFoundException e) { throw new IllegalStateException(name, e); }
                })
                .filter(c -> !c.isInterface())
                // Test doubles live in the same package and would otherwise be reviewed as if they
                // shipped. Same filter ActionFrameworkDisabledTest uses for its own stub.
                .filter(c -> !c.getName().contains("Test"))
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("the scan finds the providers, so the checks below are not vacuous")
    void scanIsNotVacuous() {
        // Without this, a scan that silently matched nothing would make every assertion here pass
        // forever while proving nothing at all.
        assertThat(providerClasses())
                .as("classpath scan of %s found no providers", SCAN_ROOT)
                .hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("the registered providers are exactly the reviewed set")
    void noProviderAppearsWithoutReview() {
        Set<String> found = providerClasses().stream()
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());

        // Adding a provider means editing this list, which forces the question "does this read only
        // the caller's own data?" to be answered by a person rather than assumed.
        assertThat(found).containsExactlyInAnyOrder(
                "Balances", "MyRequests", "PendingApprovals",
                "MyClaims", "PendingForManager",
                "Today", "MyExceptions");
    }

    @Test
    @DisplayName("no provider calls an unscoped or org-wide read")
    void providersOnlyReadTheCallersOwnData() {
        List<String> problems = providerClasses().stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(m -> "fetch".equals(m.getName()))
                .flatMap(m -> Arrays.stream(m.getDeclaringClass().getDeclaredMethods()))
                .map(Method::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .filter(FORBIDDEN_CALLS::contains)
                .toList();

        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("no provider declares a method that writes")
    void providersDeclareNoMutation() {
        List<String> problems = providerClasses().stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods())
                        .map(m -> type.getSimpleName() + "." + m.getName()))
                .filter(name -> {
                    String method = name.substring(name.indexOf('.') + 1).toLowerCase(Locale.ROOT);
                    return MUTATING_PREFIXES.stream().anyMatch(method::startsWith);
                })
                .toList();

        // A provider holds a service that can mutate - LeaveService can approve leave - so the
        // discipline is that the provider itself exposes nothing but a read. This catches the
        // moment somebody adds a convenience method that breaks it.
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("every provider declares an audience and at least one module")
    void everyProviderDeclaresItsGates() throws Exception {
        for (Class<?> type : providerClasses()) {
            AssistantDataProvider provider = instantiateWithoutDependencies(type);
            if (provider == null) continue;

            // An empty audience set would run for everyone; an empty module set would run on every
            // question. Both are the kind of default that looks harmless and is not.
            assertThat(provider.audiences()).as("%s audiences", type.getSimpleName()).isNotEmpty();
            assertThat(provider.modules()).as("%s modules", type.getSimpleName()).isNotEmpty();
            assertThat(provider.id()).as("%s id", type.getSimpleName()).isNotBlank();
            assertThat(provider.title()).as("%s title", type.getSimpleName()).isNotBlank();
        }
    }

    /**
     * Builds a provider with null dependencies purely to read its declared metadata.
     *
     * <p>{@code id()}, {@code audiences()} and {@code modules()} are constants that never touch the
     * injected service, so a null is harmless here and avoids standing up a Spring context to
     * assert four constants.
     */
    private AssistantDataProvider instantiateWithoutDependencies(Class<?> type) throws Exception {
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object[] args = new Object[constructor.getParameterCount()];
        return (AssistantDataProvider) constructor.newInstance(args);
    }
}
