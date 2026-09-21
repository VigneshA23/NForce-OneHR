package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.RetrievalResult;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides which of the caller's own records are relevant to a question, and fetches them.
 *
 * <p>Selection is driven entirely by what retrieval already matched. If the question pulled in
 * knowledge from the {@code leave} module, the leave providers run; if it pulled in nothing about
 * expenses, {@code ExpenseService} is never touched. That costs no extra model call, is
 * deterministic and explainable after the fact, and means an unrelated question never causes
 * personal data to be read or sent anywhere.
 *
 * <p>The current page contributes too, because "what's the status of this?" asked while sitting on
 * Assets &amp; Expenses is about expenses. That hint is validated against the page registry before
 * it reaches here, so it cannot widen anything.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssistantDataService {

    /**
     * A ceiling on how much of one person's record a single question can pull in.
     *
     * <p>Not a performance guard. Each provider is a real query returning real employee data into a
     * prompt that leaves the building, so the number that can fire on one question is capped
     * deliberately rather than left to however many modules happened to match.
     */
    private static final int MAX_PROVIDERS_PER_TURN = 3;

    /**
     * How relevant the page the user is looking at counts as.
     *
     * <p>High enough to win against a weak knowledge match, since someone asking a vague question
     * on the Expenses page almost certainly means expenses, but not so high that it beats a strong,
     * direct retrieval hit on another module.
     */
    private static final double CURRENT_PAGE_RELEVANCE = 0.75;

    private final List<AssistantDataProvider> providers;

    /**
     * @param context   the caller, resolved from the authenticated principal
     * @param knowledge what retrieval matched, which is what drives selection
     */
    public LiveData fetch(AssistantRequestContext context, List<RetrievalResult> knowledge) {
        Map<String, Double> moduleRelevance = moduleRelevance(context, knowledge);
        if (moduleRelevance.isEmpty()) return LiveData.empty();

        // Ranked before the cap is applied, not after. Spring hands this list over in whatever
        // order it discovered the beans, so capping in iteration order once let three loosely
        // related providers crowd out the one the question was actually about.
        List<AssistantDataProvider> candidates = providers.stream()
                .filter(provider -> mayRun(provider, context))
                .filter(provider -> relevance(provider, moduleRelevance) > 0)
                .sorted(Comparator.comparingDouble((AssistantDataProvider p) -> relevance(p, moduleRelevance))
                        .reversed()
                        // Ties broken by id so the same question selects the same providers twice
                        // running, which matters when explaining an answer after the fact.
                        .thenComparing(AssistantDataProvider::id))
                .limit(MAX_PROVIDERS_PER_TURN)
                .toList();

        List<Section> sections = new ArrayList<>();
        for (AssistantDataProvider provider : candidates) {
            fetchSafely(provider, context).ifPresent(body ->
                    sections.add(new Section(provider.id(), provider.title(), body)));
        }

        if (!sections.isEmpty()) {
            // Ids only. What was read is worth knowing; what it said is the user's business.
            log.debug("Live data providers used for this turn: {}",
                    sections.stream().map(Section::providerId).toList());
        }
        return new LiveData(List.copyOf(sections));
    }

    /** Both gates must pass before a provider is even considered for the budget. */
    private boolean mayRun(AssistantDataProvider provider, AssistantRequestContext context) {
        return provider.audiences().stream().anyMatch(context.getAudiences()::contains);
    }

    /** A provider's relevance is the best score among the modules it covers. */
    private double relevance(AssistantDataProvider provider, Map<String, Double> moduleRelevance) {
        return provider.modules().stream()
                .mapToDouble(module -> moduleRelevance.getOrDefault(module, 0d))
                .max()
                .orElse(0d);
    }

    /**
     * Best retrieval score per module, plus the current page.
     *
     * <p>Keyed on the score rather than on mere presence so that a question whose top hit is an
     * expense chunk prefers the expense provider over one matched by a chunk that scraped in at the
     * bottom of the result set.
     */
    private Map<String, Double> moduleRelevance(AssistantRequestContext context, List<RetrievalResult> knowledge) {
        Map<String, Double> relevance = new HashMap<>();
        if (knowledge != null) {
            for (RetrievalResult result : knowledge) {
                if (result.getModule() == null) continue;
                relevance.merge(result.getModule(), result.getScore(), Math::max);
            }
        }
        if (context.getCurrentModule() != null) {
            relevance.merge(context.getCurrentModule(), CURRENT_PAGE_RELEVANCE, Math::max);
        }
        return relevance;
    }

    /**
     * A provider failing must not fail the turn.
     *
     * <p>Losing the live figure leaves the static explanation, which is what the assistant returned
     * before this feature existed and is still a useful answer. Turning that into an error would
     * trade a good answer for no answer.
     */
    private Optional<String> fetchSafely(AssistantDataProvider provider, AssistantRequestContext context) {
        try {
            return provider.fetch(context);
        } catch (Exception e) {
            log.warn("Live data provider '{}' failed; continuing without it: {}", provider.id(), e.toString());
            return Optional.empty();
        }
    }

    /** One provider's contribution. */
    public record Section(String providerId, String title, String body) {}

    /** Everything fetched for one turn. */
    @Data
    @Builder
    public static class LiveData {
        private List<Section> sections;

        public static LiveData empty() {
            return LiveData.builder().sections(List.of()).build();
        }

        public boolean isEmpty() {
            return sections == null || sections.isEmpty();
        }

        public List<String> providerIds() {
            return sections == null ? List.of() : sections.stream().map(Section::providerId).toList();
        }
    }
}
