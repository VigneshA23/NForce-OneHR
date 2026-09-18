package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.KnowledgeType;
import com.nforce.onehr.ai.contract.RetrievalResult;
import com.nforce.onehr.ai.contract.ShellRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which of the caller's records get read, and — more importantly — when they do not.
 *
 * <p>Every fetch here is a real query returning real employee data into a prompt that leaves the
 * building, so "did this provider run at all" is a security question, not a performance one.
 */
class AssistantDataServiceTest {

    private AssistantRequestContext employee() {
        return context(Set.of(AudienceBucket.EMPLOYEE), ShellRole.EMPLOYEE);
    }

    private AssistantRequestContext manager() {
        return context(Set.of(AudienceBucket.EMPLOYEE, AudienceBucket.MANAGER), ShellRole.MANAGER);
    }

    private AssistantRequestContext context(Set<AudienceBucket> audiences, ShellRole role) {
        return AssistantRequestContext.builder()
                .userId(UUID.randomUUID())
                .actorEmail("someone@nforceone.com")
                .primaryRoleCode(role.name())
                .shellRole(role)
                .audiences(audiences)
                .build();
    }

    private RetrievalResult knowledgeFrom(String module) {
        return knowledgeFrom(module, 0.9);
    }

    private RetrievalResult knowledgeFrom(String module, double score) {
        return RetrievalResult.builder()
                .knowledgeId("k." + module).chunkOrdinal(0).type(KnowledgeType.ACTION)
                .module(module).pageId(module).sourceRef("yaml:test")
                .title("t").body("b").score(score).build();
    }

    /** A provider that records whether it was asked for anything. */
    private static class SpyProvider implements AssistantDataProvider {
        private final String id;
        private final Set<AudienceBucket> audiences;
        private final Set<String> modules;
        private final String body;
        final AtomicBoolean fetched = new AtomicBoolean(false);

        SpyProvider(String id, Set<AudienceBucket> audiences, Set<String> modules, String body) {
            this.id = id; this.audiences = audiences; this.modules = modules; this.body = body;
        }

        @Override public String id() { return id; }
        @Override public String title() { return "title of " + id; }
        @Override public Set<AudienceBucket> audiences() { return audiences; }
        @Override public Set<String> modules() { return modules; }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            fetched.set(true);
            return body == null ? Optional.empty() : Optional.of(body);
        }
    }

    @Test
    @DisplayName("a provider runs when its module was retrieved")
    void relevantProviderRuns() {
        SpyProvider leave = new SpyProvider("leave.balances",
                Set.of(AudienceBucket.values()), Set.of("leave"), "- Annual: 12 of 18 days remaining");
        AssistantDataService service = new AssistantDataService(List.of(leave));

        AssistantDataService.LiveData data = service.fetch(employee(), List.of(knowledgeFrom("leave")));

        assertThat(leave.fetched).isTrue();
        assertThat(data.isEmpty()).isFalse();
        assertThat(data.providerIds()).containsExactly("leave.balances");
        assertThat(data.getSections().get(0).body()).contains("12 of 18");
    }

    @Test
    @DisplayName("an unrelated question reads nothing at all")
    void irrelevantProviderIsNeverCalled() {
        SpyProvider expenses = new SpyProvider("expense.my-claims",
                Set.of(AudienceBucket.values()), Set.of("assets"), "- Travel, 400 on 2026-01-02: SUBMITTED");
        AssistantDataService service = new AssistantDataService(List.of(expenses));

        // Asking about leave must not cause a read of somebody's expense claims. This is the
        // property that keeps personal data out of prompts that had no need of it.
        AssistantDataService.LiveData data = service.fetch(employee(), List.of(knowledgeFrom("leave")));

        assertThat(expenses.fetched).isFalse();
        assertThat(data.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("nothing is read when retrieval found nothing")
    void noKnowledgeMeansNoRead() {
        SpyProvider leave = new SpyProvider("leave.balances",
                Set.of(AudienceBucket.values()), Set.of("leave"), "- Annual: 12 days");
        AssistantDataService service = new AssistantDataService(List.of(leave));

        assertThat(service.fetch(employee(), List.of()).isEmpty()).isTrue();
        assertThat(leave.fetched).isFalse();
    }

    @Test
    @DisplayName("an employee never triggers a manager-only provider")
    void audienceGateIsEnforcedBeforeFetching() {
        SpyProvider approvals = new SpyProvider("leave.pending-approvals",
                Set.of(AudienceBucket.MANAGER, AudienceBucket.HR, AudienceBucket.ADMIN),
                Set.of("leave"), "3 awaiting your decision.");
        AssistantDataService service = new AssistantDataService(List.of(approvals));

        AssistantDataService.LiveData data = service.fetch(employee(), List.of(knowledgeFrom("leave")));

        // The service would return nothing for an Employee anyway, but the provider is not called
        // at all - the gate is in front of the query, not behind it.
        assertThat(approvals.fetched).isFalse();
        assertThat(data.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a manager does trigger it")
    void managerGetsApprovalData() {
        SpyProvider approvals = new SpyProvider("leave.pending-approvals",
                Set.of(AudienceBucket.MANAGER, AudienceBucket.HR, AudienceBucket.ADMIN),
                Set.of("leave"), "3 awaiting your decision.");
        AssistantDataService service = new AssistantDataService(List.of(approvals));

        assertThat(service.fetch(manager(), List.of(knowledgeFrom("leave"))).providerIds())
                .containsExactly("leave.pending-approvals");
    }

    @Test
    @DisplayName("a provider with nothing to say contributes no section")
    void emptyResultsAreOmitted() {
        SpyProvider empty = new SpyProvider("expense.my-claims",
                Set.of(AudienceBucket.values()), Set.of("assets"), null);
        AssistantDataService service = new AssistantDataService(List.of(empty));

        AssistantDataService.LiveData data = service.fetch(employee(), List.of(knowledgeFrom("assets")));

        assertThat(empty.fetched).isTrue();
        // Padding the prompt with "you have no claims" spends tokens to say nothing.
        assertThat(data.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a failing provider costs its figure, not the turn")
    void failureIsSwallowed() {
        AssistantDataProvider broken = new SpyProvider("leave.balances",
                Set.of(AudienceBucket.values()), Set.of("leave"), "x") {
            @Override
            public Optional<String> fetch(AssistantRequestContext context) {
                throw new IllegalStateException("database is on fire");
            }
        };
        AssistantDataService service = new AssistantDataService(List.of(broken));

        // Losing the live number leaves the static explanation, which is a worse answer but still
        // an answer. Throwing would trade that for none.
        assertThat(service.fetch(employee(), List.of(knowledgeFrom("leave"))).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("the cap keeps the most relevant providers, not the first ones listed")
    void capIsAppliedByRelevanceNotByInjectionOrder() {
        // Order here mimics Spring handing the beans over in an arbitrary order, with the provider
        // the question is really about listed last.
        List<AssistantDataProvider> asSpringMightInject = List.of(
                new SpyProvider("attendance.today", Set.of(AudienceBucket.values()), Set.of("attendance"), "a"),
                new SpyProvider("attendance.my-exceptions", Set.of(AudienceBucket.values()), Set.of("attendance"), "b"),
                new SpyProvider("leave.balances", Set.of(AudienceBucket.values()), Set.of("leave"), "c"),
                new SpyProvider("expense.my-claims", Set.of(AudienceBucket.values()), Set.of("assets"), "d"));
        AssistantDataService service = new AssistantDataService(asSpringMightInject);

        // An expense question: the expense chunk is the strongest hit, the others scraped in.
        AssistantDataService.LiveData data = service.fetch(employee(), List.of(
                knowledgeFrom("attendance", 0.62),
                knowledgeFrom("leave", 0.64),
                knowledgeFrom("assets", 0.91)));

        // Caught against the real system: capping in iteration order silently dropped the only
        // provider that mattered and kept three that did not.
        assertThat(data.providerIds()).contains("expense.my-claims");
        assertThat(data.providerIds()).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("at most three providers may fire on one turn")
    void personalDataPerTurnIsCapped() {
        List<AssistantDataProvider> many = List.of(
                new SpyProvider("p1", Set.of(AudienceBucket.values()), Set.of("leave"), "a"),
                new SpyProvider("p2", Set.of(AudienceBucket.values()), Set.of("leave"), "b"),
                new SpyProvider("p3", Set.of(AudienceBucket.values()), Set.of("leave"), "c"),
                new SpyProvider("p4", Set.of(AudienceBucket.values()), Set.of("leave"), "d"),
                new SpyProvider("p5", Set.of(AudienceBucket.values()), Set.of("leave"), "e"));
        AssistantDataService service = new AssistantDataService(many);

        // A ceiling on how much of one person's record a single question can pull into a prompt
        // that goes to a third party.
        assertThat(service.fetch(employee(), List.of(knowledgeFrom("leave"))).getSections()).hasSize(3);
    }

    @Test
    @DisplayName("the current page can make a provider relevant on its own")
    void currentPageContributesToSelection() {
        SpyProvider expenses = new SpyProvider("expense.my-claims",
                Set.of(AudienceBucket.values()), Set.of("assets"), "- Travel, 400: SUBMITTED");
        AssistantDataService service = new AssistantDataService(List.of(expenses));

        AssistantRequestContext onExpensesPage = AssistantRequestContext.builder()
                .userId(UUID.randomUUID()).actorEmail("e@nforceone.com")
                .primaryRoleCode("EMPLOYEE").shellRole(ShellRole.EMPLOYEE)
                .audiences(Set.of(AudienceBucket.EMPLOYEE))
                .currentPageId("assets").currentModule("assets")
                .build();

        // "what's the status of this?" asked while sitting on Assets & Expenses is about expenses.
        // The page hint was already validated against the registry before reaching here.
        assertThat(service.fetch(onExpensesPage, List.of()).providerIds())
                .containsExactly("expense.my-claims");
    }
}
