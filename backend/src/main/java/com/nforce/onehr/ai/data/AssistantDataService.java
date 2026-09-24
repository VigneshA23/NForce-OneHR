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
import java.util.stream.Collectors;

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
 *
 * <p><strong>Selection is diverse across record types, not just ranked by score.</strong> Several
 * distinct record types can legitimately share one knowledge module - Regularization, Work From
 * Home/Partial Day and Overtime all match {@code attendance}, the way Leave and Expense both match
 * {@code approvals} on the Approval Center page. A flat top-N-by-score selection let whichever type
 * happened to have the most providers, or the strongest incidental retrieval match, fill every slot
 * before a second, equally relevant type ever got considered - concretely, three Leave providers
 * crowding out Expense's on a question that was about neither in particular. Providers are grouped
 * by record-type "family" (their id up to the first '.') and selected round-robin across families in
 * relevance order, so no single family can claim a second slot before every other relevant family has
 * had its first.
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
     *
     * <p>Raised from 3 to 4 (ONEHR - a "do I have any penalties" question was answered as "no
     * penalties" for an employee with a real active one, reproduced live on the Attendance page).
     * The primary fix for that bug is not this number: it is that {@code action.attendance.penalty}
     * and {@code action.attendance.my-exceptions} now carry their own specific knowledge module
     * ({@code penalties}, {@code exceptions}) instead of the generic {@code attendance} every other
     * attendance-adjacent provider shares, so a question actually about penalties now scores
     * {@code attendance.my-penalties} above its own sibling {@code attendance.my-exceptions} within
     * their family, and wins that family's first-round pick outright rather than needing a second
     * round that other, merely page-boosted families kept consuming first. This constant is now only
     * a modest safety margin above the original 3, for the case a question is genuinely about both
     * exceptions and penalties at once and neither outscores the other within their shared family -
     * that still costs a second round, and this is one slot of headroom for it.
     */
    private static final int MAX_PROVIDERS_PER_TURN = 4;

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
        Map<String, Double> knowledgeRelevance = knowledgeRelevance(knowledge);
        Map<String, Double> moduleRelevance = withCurrentPage(context, knowledgeRelevance);
        if (moduleRelevance.isEmpty()) return LiveData.empty();

        List<AssistantDataProvider> eligible = providers.stream()
                .filter(provider -> mayRun(provider, context))
                .filter(provider -> relevance(provider, moduleRelevance) > 0)
                .toList();

        List<AssistantDataProvider> candidates = selectDiverse(eligible, moduleRelevance, knowledgeRelevance);

        List<Section> sections = new ArrayList<>();
        for (AssistantDataProvider provider : candidates) {
            fetchSafely(provider, context).ifPresent(body ->
                    sections.add(new Section(provider.id(), provider.title(), provider.scope(), body)));
        }

        if (!sections.isEmpty()) {
            // Ids only. What was read is worth knowing; what it said is the user's business.
            log.debug("Live data providers used for this turn: {}",
                    sections.stream().map(Section::providerId).toList());
        }
        return new LiveData(List.copyOf(sections));
    }

    /**
     * Orders providers so that record-type diversity wins over raw score once every relevant
     * family has had a turn.
     *
     * <p>Round-robin: each family's own candidates are ranked internally by relevance, and families
     * are visited in order of their own best candidate's score. One candidate is taken from each
     * family per pass; a family only contributes a second candidate once every other relevant family
     * has contributed its first. The cap still applies to the total — this changes which providers
     * fill it, not how many.
     *
     * <p>Ties are broken by how well the provider's own modules matched <em>retrieved knowledge</em>,
     * before falling back to the id. Sitting on a page gives every provider tagged with that page's
     * module the same {@link #CURRENT_PAGE_RELEVANCE}, so on the Attendance page a dozen providers
     * tie; ranking that tie alphabetically meant "what time did I check in today" could lose its one
     * family slot to whichever sibling's id sorted first. A provider the question actually retrieved
     * knowledge for now wins that tie, even when the match scored below the page's own weight.
     */
    private List<AssistantDataProvider> selectDiverse(List<AssistantDataProvider> eligible,
                                                      Map<String, Double> moduleRelevance,
                                                      Map<String, Double> knowledgeRelevance) {
        if (eligible.isEmpty()) return List.of();

        Comparator<AssistantDataProvider> bestFirst = Comparator
                .comparingDouble((AssistantDataProvider p) -> relevance(p, moduleRelevance)).reversed()
                .thenComparing(Comparator.comparingDouble(
                        (AssistantDataProvider p) -> relevance(p, knowledgeRelevance)).reversed())
                .thenComparing(AssistantDataProvider::id);

        Map<String, List<AssistantDataProvider>> byFamily = eligible.stream()
                .collect(Collectors.groupingBy(AssistantDataService::family));
        for (List<AssistantDataProvider> members : byFamily.values()) {
            members.sort(bestFirst);
        }

        List<String> familyOrder = byFamily.keySet().stream()
                .sorted(Comparator.comparing((String family) -> byFamily.get(family).get(0), bestFirst)
                        .thenComparing(Comparator.naturalOrder()))
                .toList();

        List<AssistantDataProvider> selected = new ArrayList<>();
        Map<String, Integer> nextIndex = new HashMap<>();
        boolean progressed = true;
        while (selected.size() < MAX_PROVIDERS_PER_TURN && progressed) {
            progressed = false;
            for (String family : familyOrder) {
                if (selected.size() >= MAX_PROVIDERS_PER_TURN) break;
                List<AssistantDataProvider> members = byFamily.get(family);
                int index = nextIndex.getOrDefault(family, 0);
                if (index < members.size()) {
                    selected.add(members.get(index));
                    nextIndex.put(family, index + 1);
                    progressed = true;
                }
            }
        }
        return selected;
    }

    /**
     * A provider's record-type family: its id up to (not including) the first '.'.
     *
     * <p>Deliberately coarser than the knowledge module. Regularization, Work From Home/Partial Day
     * and Overtime providers all declare {@code attendance} among their modules, but they are three
     * distinct record types that each deserve a fair turn — grouping by module instead of by family
     * would let them crowd each other out exactly the way Leave once crowded out Expense.
     */
    private static String family(AssistantDataProvider provider) {
        int dot = provider.id().indexOf('.');
        return dot < 0 ? provider.id() : provider.id().substring(0, dot);
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
     * Best retrieval score per module.
     *
     * <p>Keyed on the score rather than on mere presence so that a question whose top hit is an
     * expense chunk prefers the expense provider over one matched by a chunk that scraped in at the
     * bottom of the result set.
     */
    private Map<String, Double> knowledgeRelevance(List<RetrievalResult> knowledge) {
        Map<String, Double> relevance = new HashMap<>();
        if (knowledge != null) {
            for (RetrievalResult result : knowledge) {
                if (result.getModule() == null) continue;
                relevance.merge(result.getModule(), result.getScore(), Math::max);
            }
        }
        return relevance;
    }

    /** {@link #knowledgeRelevance} plus the page the user is on. */
    private Map<String, Double> withCurrentPage(AssistantRequestContext context, Map<String, Double> knowledgeRelevance) {
        Map<String, Double> relevance = new HashMap<>(knowledgeRelevance);
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
    public record Section(String providerId, String title, DataScope scope, String body) {}

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
