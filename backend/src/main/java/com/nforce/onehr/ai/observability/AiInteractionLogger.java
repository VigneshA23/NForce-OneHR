package com.nforce.onehr.ai.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AssistantResponse;
import com.nforce.onehr.ai.contract.AssistantResponseType;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.LlmCompletion;
import com.nforce.onehr.ai.contract.NavigationAction;
import com.nforce.onehr.ai.contract.RetrievalResult;
import com.nforce.onehr.ai.entity.AiInteractionLog;
import com.nforce.onehr.ai.repository.AiInteractionLogRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Writes one {@link AiInteractionLog} row per assistant turn.
 *
 * <p>Answers the question a support ticket three months later actually asks: not "what did the
 * assistant say", which is in the conversation, but "what was it working from when it said it" —
 * which knowledge was retrieved, at what score, for which audience, from which model.
 *
 * <p><strong>Two rules govern everything here.</strong>
 *
 * <p>First, <em>logging must never break a turn</em>. Every write is wrapped and swallowed. An
 * observability table filling up, or a JSON serialisation surprise, must not be able to turn a
 * working answer into an error — the user came here for help with their leave request, and the
 * metrics are for us.
 *
 * <p>Second, <em>no message text goes in</em>. {@link Turn} carries {@code questionChars}, an int,
 * rather than the question, so there is no field for the text to be put in by a later change that
 * seemed reasonable at the time. {@code com.nforce.onehr} logs at DEBUG in every environment, so
 * nothing here reaches the application logger either.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiInteractionLogger {

    /** The feature is switched off. */
    public static final String DISABLED = "DISABLED";
    /** Nothing was asked. */
    public static final String EMPTY_MESSAGE = "EMPTY_MESSAGE";
    /** Over {@code app.ai.limits.max-message-chars}. */
    public static final String MESSAGE_TOO_LONG = "MESSAGE_TOO_LONG";
    /** Over the per-user hourly budget. */
    public static final String RATE_LIMITED = "RATE_LIMITED";
    /** Retrieval returned nothing the caller was allowed to see; the model was never called. */
    public static final String NO_KNOWLEDGE = "NO_KNOWLEDGE";
    /** The embedding call failed, so retrieval could not run. */
    public static final String RETRIEVAL_UNAVAILABLE = "RETRIEVAL_UNAVAILABLE";
    /** The completion call failed. */
    public static final String PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";
    /**
     * The model was called and answered, but the answer came back UNKNOWN — unparseable output,
     * or the model correctly declining. Derived rather than passed, so the two are not
     * distinguished here; a spike in this column is a prompt or retrieval problem either way.
     */
    public static final String UNKNOWN_ANSWER = "UNKNOWN_ANSWER";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiInteractionLogRepository repository;

    /**
     * Records a completed turn.
     *
     * <p>{@code REQUIRES_NEW} so the row survives independently: a turn's own transaction rolling
     * back is exactly when the record of it is most worth having.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Turn turn) {
        try {
            repository.save(toEntity(turn));
        } catch (Exception e) {
            // Intentionally swallowed, and logged without any turn detail: the failure is ours,
            // and the user's answer has already been produced.
            log.warn("Could not record AI interaction: {}", e.toString());
        }
    }

    /**
     * Attaches a rating to the most recent turn of a conversation.
     *
     * <p>Applied to the newest turn rather than to a client-supplied turn id, because that is what
     * a thumbs-down on the answer just rendered actually means, and it gives the client nothing to
     * get wrong or to aim somewhere else.
     *
     * @return true when a row was updated
     */
    @Transactional
    public boolean recordFeedback(UUID conversationId, UUID userId, String rating, String comment) {
        String normalised = rating == null ? "" : rating.trim().toUpperCase();
        if (!AiInteractionLog.FEEDBACK_UP.equals(normalised) && !AiInteractionLog.FEEDBACK_DOWN.equals(normalised)) {
            // Rejected here rather than at the DB CHECK, so a bad value is a no-op instead of an
            // exception on a path whose whole purpose is to be unobtrusive.
            log.debug("Ignoring AI feedback with unrecognised rating");
            return false;
        }
        try {
            List<AiInteractionLog> newest = repository.findByConversationIdAndUserIdOrderByCreatedAtDesc(
                    conversationId, userId, PageRequest.of(0, 1));
            if (newest.isEmpty()) return false;

            AiInteractionLog entry = newest.get(0);
            entry.setFeedbackRating(normalised);
            entry.setFeedbackComment(comment == null || comment.isBlank() ? null : comment.trim());
            entry.setFeedbackAt(Instant.now());
            repository.save(entry);
            return true;
        } catch (Exception e) {
            log.warn("Could not record AI feedback: {}", e.toString());
            return false;
        }
    }

    private AiInteractionLog toEntity(Turn turn) {
        AssistantRequestContext context = turn.getContext();
        AssistantResponse response = turn.getResponse();
        LlmCompletion completion = turn.getCompletion();
        List<RetrievalResult> knowledge = turn.getKnowledge() == null ? List.of() : turn.getKnowledge();

        AssistantResponseType type = response == null ? AssistantResponseType.UNKNOWN : response.getType();
        String errorCode = turn.getErrorCode();
        if (errorCode == null && type == AssistantResponseType.UNKNOWN) {
            errorCode = UNKNOWN_ANSWER;
        }

        return AiInteractionLog.builder()
                .conversationId(turn.getConversationId())
                .userId(context.getUserId())
                .audienceBuckets(joinAudiences(context))
                .shellRole(context.getShellRole() == null ? "EMPLOYEE" : context.getShellRole().name())
                .currentPageId(context.getCurrentPageId())
                .responseType(type == null ? AssistantResponseType.UNKNOWN.name() : type.name())
                .confidence(response == null || response.getConfidence() == null
                        ? null : response.getConfidence().name())
                .navigationPageId(navigationPageId(response))
                .retrievedKnowledgeIds(toJsonArray(knowledge))
                .retrievedCount(knowledge.size())
                .topScore(topScore(knowledge))
                .provider(completion == null ? null : completion.getProvider())
                .model(completion == null ? null : completion.getModel())
                .promptTokens(completion == null ? null : completion.getPromptTokens())
                .completionTokens(completion == null ? null : completion.getCompletionTokens())
                .embeddingPromptTokens(turn.getEmbeddingPromptTokens())
                .apiCallAttempts(turn.getApiCallAttempts())
                .latencyMs((int) Math.min(turn.getLatencyMs(), Integer.MAX_VALUE))
                .success(errorCode == null)
                .errorCode(errorCode)
                .questionChars(turn.getQuestionChars())
                .createdAt(Instant.now())
                .build();
    }

    /** Sorted, so two turns with the same audiences always produce the same string to group on. */
    private String joinAudiences(AssistantRequestContext context) {
        if (context.getAudiences() == null || context.getAudiences().isEmpty()) return "";
        return context.getAudiences().stream()
                .map(AudienceBucket::name)
                .sorted()
                .collect(Collectors.joining(","));
    }

    private String navigationPageId(AssistantResponse response) {
        if (response == null) return null;
        NavigationAction navigation = response.getNavigation();
        return navigation == null ? null : navigation.getPageId();
    }

/**
     * Best score among what was retrieved, or null when nothing was.
     *
     * <p>Not the best score <em>before</em> min-score filtering, which is what would really help
     * tune the threshold — that number is discarded inside the SQL, and surfacing it would mean
     * changing the retriever's contract. Worth doing when the threshold next needs tuning; not
     * worth doing speculatively.
     */
    private Double topScore(List<RetrievalResult> knowledge) {
        return knowledge.isEmpty()
                ? null
                : knowledge.stream().mapToDouble(RetrievalResult::getScore).max().orElse(0d);
    }

    /** Rank order preserved — which knowledge came first is part of what is being reviewed. */
    private String toJsonArray(List<RetrievalResult> knowledge) {
        try {
            return MAPPER.writeValueAsString(knowledge.stream()
                    .map(RetrievalResult::getKnowledgeId)
                    .filter(java.util.Objects::nonNull)
                    .toList());
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * Everything worth knowing about one turn.
     *
     * <p>Note what is absent: the question, the answer, and any role or permission supplied by the
     * caller. Length stands in for the question; the answer is in the conversation; authority came
     * from the database and is recorded as the buckets it resolved to.
     */
    @Data
    @Builder
    public static class Turn {
        private AssistantRequestContext context;
        private UUID conversationId;
        /** The question's length. Never the question. */
        private int questionChars;
        private List<RetrievalResult> knowledge;
        private LlmCompletion completion;
        private AssistantResponse response;
        private String errorCode;
        private long latencyMs;
        /** See {@link AiInteractionLog#getEmbeddingPromptTokens()}. */
        private Integer embeddingPromptTokens;
        /** See {@link AiInteractionLog#getApiCallAttempts()}. */
        private int apiCallAttempts;
    }
}
