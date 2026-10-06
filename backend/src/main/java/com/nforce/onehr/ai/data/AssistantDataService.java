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
import java.util.regex.Pattern;
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
     * Raising it was not enough on its own: on the Attendance page four families match, so every
     * slot goes in the first round and the attendance family never gets a second one. That is why
     * the caller's log, exceptions and penalties are one provider ({@code attendance.my-history})
     * rather than three - a question about late days and penalties together only needs one slot.
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
        return fetch(context, knowledge, null);
    }

    /**
     * As {@link #fetch(AssistantRequestContext, List)}, additionally passing the raw question
     * through to {@link AssistantDataProvider#fetch(AssistantRequestContext, String)} for the
     * handful of providers that read a date range out of it (see that method's own Javadoc). The
     * question never changes which providers are selected or what they are allowed to read -
     * only, for those few, which dates within their already-authorised scope they read.
     *
     * @param question the user's question, verbatim - never itself a source of authorisation
     */
    public LiveData fetch(AssistantRequestContext context, List<RetrievalResult> knowledge, String question) {
        Map<String, Double> knowledgeRelevance = knowledgeRelevance(knowledge);
        questionRelevance(question).forEach((module, score) -> knowledgeRelevance.merge(module, score, Math::max));
        Map<String, Double> moduleRelevance = withCurrentPage(context, knowledgeRelevance);

        List<AssistantDataProvider> eligible = providers.stream()
                .filter(provider -> !provider.consultedEveryTurn())
                .filter(provider -> mayRun(provider, context))
                .filter(provider -> relevance(provider, moduleRelevance) > 0)
                .toList();

        List<AssistantDataProvider> candidates = new ArrayList<>(selectDiverse(eligible, moduleRelevance, knowledgeRelevance));
        providers.stream()
                .filter(AssistantDataProvider::consultedEveryTurn)
                .filter(provider -> mayRun(provider, context))
                .forEach(candidates::add);

        List<Section> sections = new ArrayList<>();
        for (AssistantDataProvider provider : candidates) {
            fetchSafely(provider, context, question).ifPresent(body ->
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

    /**
     * Wording that names a record type outright, mapped to the module of the one provider holding
     * those rows. Retrieval ranks by similarity, and "show my previous 5 attendance records" scored
     * closer to today's attendance than to the history - so the model got no rows and answered with
     * directions (ONEHR), while "last 5" worked. Deterministic, the same way {@link MyTeamDateRange}
     * reads a period: a question that says "records" or "rejected leave" gets those rows.
     */
    private static final Map<Pattern, String> QUESTION_MODULES = Map.of(
            Pattern.compile("\\battendance\\s+(records?|history|log|rows?|entries)\\b"
                    + "|\\b(last|previous|past|recent|prior)\\s+\\d+\\s+(attendance|records?|entries|days?)\\b"
                    + "|\\b(present|absent)\\b", Pattern.CASE_INSENSITIVE), "attendance-history",
            Pattern.compile("\\bleave\\s+(requests?|applications?|rejections?)\\b"
                    + "|\\b(reject\\w*|approved|pending|cancell?ed|withdrawn)\\b.*\\bleaves?\\b"
                    + "|\\bleaves?\\b.*\\b(reject\\w*|approved|pending|cancell?ed|withdrawn)\\b", Pattern.CASE_INSENSITIVE), "leave-requests",
            Pattern.compile("\\b(employees|people|staff|department|how\\s+many)\\b.*\\b(absent|present|checked\\s+in|late|attendance)\\b",
                    Pattern.CASE_INSENSITIVE), "org-attendance",
            // The caller's own work details. On a page whose own providers fill every slot (six
            // families on Attendance), "who is my manager" got no profile and the model invented one (ONEHR).
            Pattern.compile("\\bmy\\s+(reporting\\s+|line\\s+)?(manager|supervisor|boss)\\b"
                    + "|\\b(who|whom)\\s+(do|should)\\s+i\\s+report\\b|\\bi\\s+report\\s+to\\b"
                    + "|\\bmy\\s+(employee\\s+(code|id|number)|designation|department|business\\s+unit|employment\\s+type"
                    + "|work\\s+(mode|location)|joining\\s+date|date\\s+of\\s+joining|probation|confirmation\\s+date)\\b"
                    + "|\\bwhat\\s+department\\s+am\\s+i\\b|\\bwhich\\s+department\\s+am\\s+i\\b|\\bwhen\\s+did\\s+i\\s+join\\b"
                    + "|\\bam\\s+i\\s+(still\\s+)?on\\s+probation\\b", Pattern.CASE_INSENSITIVE), "my-profile");

    /** As strong as a direct retrieval hit: the question named the record type in so many words. */
    private static final double QUESTION_RELEVANCE = 0.9;

    private static final Pattern ATTENDANCE = Pattern.compile("\\b(attendance|check[- ]?ins?|punch(es)?)\\b", Pattern.CASE_INSENSITIVE);

    /** "my team", "employees", "who ..." - about the caller's reports (or the organisation), not the caller. */
    private static final Pattern TEAM_SCOPE = Pattern.compile(
            "\\b(my|our)\\s+(team|direct\\s+reports?|reportees|subordinates)\\b|\\bteam\\s*members?\\b"
                    + "|\\b(employees?|reportees|subordinates|staff|members|people)\\b|\\bwho\\b|\\bwhich\\s+of\\b",
            Pattern.CASE_INSENSITIVE);
    /** Every attendance discrepancy My Team's attendance rows show. */
    private static final Pattern DISCREPANCY = Pattern.compile(
            "\\b(absent\\w*|absences?|late(ness|comers?)?|punch\\w*|check[- ]?(ins?|outs?)|left\\s+early|early\\s+(exit|departure|leaving|logout)s?"
                    + "|half[- ]?days?|discrepanc\\w*|irregular\\w*|attendance|on\\s+time|punctual\\w*)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PENALTY = Pattern.compile("\\bpenal\\w*", Pattern.CASE_INSENSITIVE);
    private static final Pattern REGULARIZATION = Pattern.compile("\\bregulari[sz]\\w*", Pattern.CASE_INSENSITIVE);
    private static final Pattern WFH = Pattern.compile(
            "\\b(wfh|work(ing|s)?\\s+from\\s+home|remote(ly)?|on\\s+duty)\\b", Pattern.CASE_INSENSITIVE);
    /** "what leave types can I apply for", "my leave balance" - the balance block lists every type held. */
    private static final Pattern LEAVE_TYPES = Pattern.compile(
            "\\bleave\\s+(types?|balances?|entitlements?)\\b|\\btypes?\\s+of\\s+leaves?\\b"
                    + "|\\bleaves?\\b.*\\b(apply|avail\\w*|left|remaining)\\b|\\b(apply|avail\\w*)\\b.*\\bleaves?\\b",
            Pattern.CASE_INSENSITIVE);

    private static Map<String, Double> questionRelevance(String question) {
        if (question == null) return Map.of();
        Map<String, Double> relevance = new HashMap<>();
        QUESTION_MODULES.forEach((pattern, module) -> {
            if (pattern.matcher(question).find()) relevance.put(module, QUESTION_RELEVANCE);
        });
        // "my attendance for the past week", "... in August": any period but today alone is the
        // history's, which reads exactly that period. Only presence matters here, so the JVM date is fine.
        if (ATTENDANCE.matcher(question).find() && MyTeamDateRange.named(question, java.time.LocalDate.now())
                .filter(r -> !"today".equals(r.label())).isPresent()) {
            relevance.put("attendance-history", QUESTION_RELEVANCE);
        }
        if (LEAVE_TYPES.matcher(question).find()) relevance.put("leave-balances", QUESTION_RELEVANCE);
        // Regularization shares "attendance" with five other families and sorts fifth for four
        // slots, so "how many regularizations did I raise" got no rows at all (ONEHR).
        if (REGULARIZATION.matcher(question).find()) relevance.put("regularization", QUESTION_RELEVANCE);
        // A team question gets the team's rows, never only the caller's own: "how many in my team
        // were absent yesterday" was answered from the manager's own empty record (ONEHR).
        if (TEAM_SCOPE.matcher(question).find()) {
            relevance.remove("attendance-history");
            // The team's regularizations are the approver queue (ONEHR - "pending regularization
            // requests for my team" got only a link to My Team's reports).
            if (relevance.remove("regularization") != null) relevance.put("regularization-approvals", QUESTION_RELEVANCE);
            if (DISCREPANCY.matcher(question).find()) relevance.put("my-team-attendance", QUESTION_RELEVANCE);
            if (PENALTY.matcher(question).find()) {
                relevance.put("team-penalties", QUESTION_RELEVANCE);
                relevance.put("org-penalties", QUESTION_RELEVANCE);
            }
            if (WFH.matcher(question).find()) {
                relevance.put("my-team-overview", QUESTION_RELEVANCE);
                relevance.put("org-attendance", QUESTION_RELEVANCE);
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
    private Optional<String> fetchSafely(AssistantDataProvider provider, AssistantRequestContext context, String question) {
        try {
            return provider.fetch(context, question);
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
