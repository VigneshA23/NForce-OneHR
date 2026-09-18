package com.nforce.onehr.ai.eval;

import com.nforce.onehr.ai.contract.AssistantResponseType;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.KnowledgeDocument;
import com.nforce.onehr.ai.contract.PageReference;
import com.nforce.onehr.ai.contract.ShellRole;
import com.nforce.onehr.ai.knowledge.YamlKnowledgeSource;
import com.nforce.onehr.ai.navigation.PageRegistry;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.util.RoleUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the evaluation set honest, for free.
 *
 * <p>The live harness costs a real embedding call and a real completion call per question, so it
 * only runs when someone asks for it. That leaves a gap: between those runs, a question can quietly
 * rot. A knowledge unit gets renamed, a nav item moves between roles, and the question that was
 * guarding a specific failure is now expecting something that cannot happen — so it fails for a
 * reason that has nothing to do with the assistant, or worse, it is never noticed at all.
 *
 * <p>This runs in the ordinary suite and checks every expectation is still <em>achievable</em>: the
 * pages exist and are reachable for the asking role, the knowledge ids exist and are visible to
 * that role, the response types are real. It says nothing about whether the answers are good. It
 * says only that the questions are still answerable, which is the part that can silently stop being
 * true.
 */
class AiEvaluationSetTest {

    private static final Set<String> ROLES = Set.of("EMPLOYEE", "MANAGER", "HR_ADMIN", "SUPER_ADMIN");

    private static final Set<String> CATEGORIES = Set.of(
            "how-to", "module", "navigation", "permission", "workflow", "explanation",
            "troubleshooting", "synonym", "ambiguous", "unauthorised", "out-of-scope");

    private static List<EvaluationSet.EvalQuestion> questions;
    private static PageRegistry registry;
    private static Map<String, KnowledgeDocument> knowledgeById;

    @BeforeAll
    static void setUp() {
        questions = EvaluationSet.load();

        registry = new PageRegistry();
        ReflectionTestUtils.invokeMethod(registry, "load");

        knowledgeById = new YamlKnowledgeSource().load().stream()
                .collect(Collectors.toMap(KnowledgeDocument::getKnowledgeId, Function.identity()));
    }

    @Test
    @DisplayName("the fixture actually loaded")
    void fixtureIsNotVacuous() {
        // Guards every assertion below: a parse that silently produced nothing would make all of
        // them pass while checking no questions at all.
        assertThat(questions).hasSizeGreaterThanOrEqualTo(40);
        assertThat(knowledgeById).isNotEmpty();
        assertThat(registry.size()).isGreaterThan(20);
    }

    @Test
    @DisplayName("ids are unique")
    void idsAreUnique() {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = questions.stream()
                .map(EvaluationSet.EvalQuestion::getId)
                .filter(id -> !seen.add(id))
                .toList();
        // The scorecard is keyed by id, so a duplicate silently overwrites another question's result.
        assertThat(duplicates).isEmpty();
    }

    @Test
    @DisplayName("roles, categories and response types are real values")
    void enumeratedFieldsAreValid() {
        List<String> problems = new ArrayList<>();
        Set<String> validTypes = Arrays.stream(AssistantResponseType.values())
                .map(Enum::name).collect(Collectors.toSet());

        for (EvaluationSet.EvalQuestion q : questions) {
            if (!ROLES.contains(q.getRole())) {
                problems.add(q.getId() + ": unknown role '" + q.getRole() + "'");
            }
            if (!CATEGORIES.contains(q.getCategory())) {
                problems.add(q.getId() + ": unknown category '" + q.getCategory() + "'");
            }
            if (q.getTypes().isEmpty()) {
                // Without this the question asserts nothing about the answer's shape and would
                // pass for any response at all.
                problems.add(q.getId() + ": no expected response type");
            }
            for (String type : q.getTypes()) {
                if (!validTypes.contains(type)) {
                    problems.add(q.getId() + ": unknown response type '" + type + "'");
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("every expected page exists and the asking role can reach it")
    void navigationExpectationsAreAchievable() {
        List<String> problems = new ArrayList<>();

        for (EvaluationSet.EvalQuestion q : questions) {
            if (!q.requiresNavigation()) continue;

            ShellRole role = ShellRole.fromPrimaryRoleCode(q.getRole());
            if (!registry.exists(q.getNavigation())) {
                problems.add(q.getId() + ": pageId '" + q.getNavigation() + "' is not in the registry");
                continue;
            }
            PageReference page = registry.find(q.getNavigation(), role).orElse(null);
            if (page == null) {
                // The question expects a page this role has no variant for, so it can never pass.
                problems.add(q.getId() + ": " + q.getRole() + " cannot reach '" + q.getNavigation() + "'");
            } else if (page.isPlaceholder()) {
                // NavigationValidator refuses to emit placeholders, so expecting one is expecting
                // a guarantee to be broken.
                problems.add(q.getId() + ": '" + q.getNavigation() + "' is a Phase 2 placeholder");
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("every expected knowledge id exists and is visible to the asking role")
    void knowledgeExpectationsAreAchievable() {
        List<String> problems = new ArrayList<>();

        for (EvaluationSet.EvalQuestion q : questions) {
            Set<AudienceBucket> audiences = audiencesFor(q.getRole());
            boolean anyVisible = q.getKnowledge().isEmpty();

            for (String knowledgeId : q.getKnowledge()) {
                KnowledgeDocument document = knowledgeById.get(knowledgeId);
                if (document == null) {
                    problems.add(q.getId() + ": knowledge id '" + knowledgeId + "' no longer exists");
                    continue;
                }
                if (document.getAudiences().stream().anyMatch(audiences::contains)) anyVisible = true;
            }

            if (!anyVisible) {
                // Retrieval filters on audience in SQL, so a question expecting knowledge its own
                // role cannot see is asserting something the system is built to prevent.
                problems.add(q.getId() + ": none of " + q.getKnowledge()
                        + " is visible to " + q.getRole() + " " + audiences);
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("a question that must not navigate does not also demand a page")
    void contradictoryExpectationsAreRejected() {
        List<String> problems = questions.stream()
                .filter(q -> q.forbidsNavigation() && !q.getKnowledge().isEmpty() && q.getTypes().contains("NAVIGATION"))
                .map(q -> q.getId() + ": forbids navigation but expects type NAVIGATION")
                .toList();
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("mustNotMention is never a substring of mustMention on the same question")
    void assertionsDoNotContradictThemselves() {
        List<String> problems = new ArrayList<>();
        for (EvaluationSet.EvalQuestion q : questions) {
            for (String required : q.getMustMention()) {
                for (String forbidden : q.getMustNotMention()) {
                    // An unpassable question. Easy to write by accident when tightening a
                    // mustNotMention, and it would look like a model failure forever.
                    if (required.toLowerCase().contains(forbidden.toLowerCase())) {
                        problems.add(q.getId() + ": requires '" + required + "' but forbids '" + forbidden + "'");
                    }
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("all four roles and every category are covered")
    void coverageIsComplete() {
        Set<String> rolesAsked = questions.stream()
                .map(EvaluationSet.EvalQuestion::getRole).collect(Collectors.toSet());
        assertThat(rolesAsked).containsExactlyInAnyOrderElementsOf(ROLES);

        Set<String> categoriesCovered = questions.stream()
                .map(EvaluationSet.EvalQuestion::getCategory).collect(Collectors.toSet());
        assertThat(categoriesCovered).containsExactlyInAnyOrderElementsOf(CATEGORIES);
    }

    @Test
    @DisplayName("the read-only guarantee is probed from every privilege level")
    void everyRoleIsAskedToDoSomething() {
        // "Just do it for me" is the request most likely to talk a helpful model into claiming it
        // acted. It has to be asked by someone who cannot, someone who can, and someone who can do
        // anything - the temptation is different at each level.
        Set<String> rolesAskedToAct = questions.stream()
                .filter(q -> q.getMustNotMention().stream()
                        .anyMatch(phrase -> phrase.toLowerCase().startsWith("i have")
                                || phrase.equalsIgnoreCase("done")))
                .map(EvaluationSet.EvalQuestion::getRole)
                .collect(Collectors.toSet());

        assertThat(rolesAskedToAct).contains("EMPLOYEE", "MANAGER", "SUPER_ADMIN");
    }

    /** Built through RoleUtils rather than a second table, so there is one audience hierarchy. */
    private Set<AudienceBucket> audiencesFor(String roleCode) {
        Set<Role> roles = new HashSet<>();
        roles.add(Role.builder().id(1).code("EMPLOYEE").displayName("Employee").build());
        if (!"EMPLOYEE".equals(roleCode)) {
            // Mirrors UserManagementService#rolesFor, which grants every non-EMPLOYEE role holder
            // the base EMPLOYEE role as well - so a Manager really is {EMPLOYEE, MANAGER}.
            roles.add(Role.builder().id(2).code(roleCode).displayName(roleCode).build());
        }
        return AudienceBucket.from(RoleUtils.audienceBuckets(roles));
    }
}
