package com.nforce.onehr.ai.observability;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AssistantResponse;
import com.nforce.onehr.ai.contract.AssistantResponseType;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.ConfidenceLevel;
import com.nforce.onehr.ai.contract.KnowledgeType;
import com.nforce.onehr.ai.contract.LlmCompletion;
import com.nforce.onehr.ai.contract.NavigationAction;
import com.nforce.onehr.ai.contract.RetrievalResult;
import com.nforce.onehr.ai.contract.ShellRole;
import com.nforce.onehr.ai.entity.AiInteractionLog;
import com.nforce.onehr.ai.repository.AiInteractionLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What gets recorded about a turn, and — more importantly — what does not.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiInteractionLoggerTest {

    @Mock private AiInteractionLogRepository repository;
    @InjectMocks private AiInteractionLogger logger;

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    private AssistantRequestContext context() {
        return AssistantRequestContext.builder()
                .userId(userId)
                .primaryRoleCode("HR_ADMIN")
                .shellRole(ShellRole.HR_ADMIN)
                .audiences(Set.of(AudienceBucket.HR, AudienceBucket.EMPLOYEE))
                .currentPageId("leave")
                .build();
    }

    private AiInteractionLog captureSaved() {
        ArgumentCaptor<AiInteractionLog> captor = ArgumentCaptor.forClass(AiInteractionLog.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a successful turn records retrieval, model and cost")
    void successfulTurnIsRecordedInFull() {
        logger.record(AiInteractionLogger.Turn.builder()
                .context(context())
                .conversationId(conversationId)
                .questionChars(31)
                .knowledge(List.of(
                        result("action.leave.apply", 0.91),
                        result("workflow.leave.approval", 0.74)))
                .completion(LlmCompletion.builder()
                        .provider("mistral").model("ministral-8b-latest")
                        .promptTokens(1200).completionTokens(180).build())
                .response(AssistantResponse.builder()
                        .type(AssistantResponseType.HOW_TO)
                        .confidence(ConfidenceLevel.HIGH)
                        .navigation(NavigationAction.builder().pageId("leave").build())
                        .build())
                .latencyMs(842)
                .build());

        AiInteractionLog saved = captureSaved();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getConversationId()).isEqualTo(conversationId);
        assertThat(saved.getShellRole()).isEqualTo("HR_ADMIN");
        assertThat(saved.getResponseType()).isEqualTo("HOW_TO");
        assertThat(saved.getConfidence()).isEqualTo("HIGH");
        assertThat(saved.getNavigationPageId()).isEqualTo("leave");
        assertThat(saved.getCurrentPageId()).isEqualTo("leave");
        assertThat(saved.getModel()).isEqualTo("ministral-8b-latest");
        assertThat(saved.getPromptTokens()).isEqualTo(1200);
        assertThat(saved.getLatencyMs()).isEqualTo(842);
        assertThat(saved.isSuccess()).isTrue();
        assertThat(saved.getErrorCode()).isNull();
    }

    @Test
    @DisplayName("retrieved ids are stored in rank order, with the top score")
    void retrievalIsRecordedInRankOrder() {
        logger.record(AiInteractionLogger.Turn.builder()
                .context(context())
                .knowledge(List.of(result("action.leave.apply", 0.91), result("module.leave", 0.62)))
                .response(AssistantResponse.builder().type(AssistantResponseType.HOW_TO).build())
                .build());

        AiInteractionLog saved = captureSaved();
        // Order matters: "which knowledge won" is the first thing anyone asks when an answer is
        // wrong, and a sorted or de-duplicated array would lose exactly that.
        assertThat(saved.getRetrievedKnowledgeIds())
                .isEqualTo("[\"action.leave.apply\",\"module.leave\"]");
        assertThat(saved.getRetrievedCount()).isEqualTo(2);
        assertThat(saved.getTopScore()).isEqualTo(0.91);
    }

    @Test
    @DisplayName("audience buckets are sorted, so identical turns group together")
    void audienceBucketsAreStable() {
        logger.record(AiInteractionLogger.Turn.builder()
                .context(context())
                .response(AssistantResponse.builder().type(AssistantResponseType.EXPLANATION).build())
                .build());

        // A Set's iteration order is not stable across runs, so an unsorted join would produce both
        // "EMPLOYEE,HR" and "HR,EMPLOYEE" for the same user and split every report in two.
        assertThat(captureSaved().getAudienceBuckets()).isEqualTo("EMPLOYEE,HR");
    }

    @Test
    @DisplayName("an explicit error code marks the turn unsuccessful")
    void explicitErrorCodeIsRecorded() {
        logger.record(AiInteractionLogger.Turn.builder()
                .context(context())
                .response(AssistantResponse.builder().type(AssistantResponseType.UNKNOWN).build())
                .errorCode(AiInteractionLogger.RATE_LIMITED)
                .build());

        AiInteractionLog saved = captureSaved();
        assertThat(saved.getErrorCode()).isEqualTo(AiInteractionLogger.RATE_LIMITED);
        assertThat(saved.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("an UNKNOWN answer with no explicit code is recorded as UNKNOWN_ANSWER")
    void unknownAnswersAreDistinguishedFromSuccesses() {
        logger.record(AiInteractionLogger.Turn.builder()
                .context(context())
                .knowledge(List.of(result("action.leave.apply", 0.91)))
                .completion(LlmCompletion.builder().provider("mistral").model("m").build())
                .response(AssistantResponse.builder().type(AssistantResponseType.UNKNOWN).build())
                .build());

        AiInteractionLog saved = captureSaved();
        // Retrieval found something and the model was paid for, yet the user got nothing. That is
        // the signal worth having, and it is invisible if it is filed as a success.
        assertThat(saved.getErrorCode()).isEqualTo(AiInteractionLogger.UNKNOWN_ANSWER);
        assertThat(saved.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("a logging failure never reaches the caller")
    void loggingFailuresAreSwallowed() {
        when(repository.save(any())).thenThrow(new RuntimeException("table is gone"));

        // The user asked a question and got an answer. Losing the metric is a problem for us; it
        // is not a reason to turn their answer into an error.
        assertThatCode(() -> logger.record(AiInteractionLogger.Turn.builder()
                .context(context())
                .response(AssistantResponse.builder().type(AssistantResponseType.HOW_TO).build())
                .build())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("nothing in this table can hold message text")
    void noFieldCanCarryTheQuestionOrAnswer() throws Exception {
        // A structural guard rather than a behavioural one. The rule "do not log employee free
        // text" survives only as long as there is nowhere to put it, so this fails the moment
        // someone adds a plausible-looking place.
        assertNoTextFields(AiInteractionLogger.Turn.class, "question", "answer", "message", "content", "text");
        assertNoTextFields(AiInteractionLog.class, "question", "answer", "message", "content", "text");
    }

    private void assertNoTextFields(Class<?> type, String... forbidden) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() != String.class) continue;
            String name = field.getName().toLowerCase(Locale.ROOT);
            for (String word : forbidden) {
                // feedbackComment is the one deliberate exception: text the user volunteered as
                // feedback, covered by the same retention note and never sent to the logger.
                if (name.contains(word) && !name.startsWith("feedback")) {
                    throw new AssertionError(type.getSimpleName() + "." + field.getName()
                            + " looks like it could hold message text, which this table must not store");
                }
            }
        }
    }

    @Test
    @DisplayName("feedback lands on the newest turn of the conversation")
    void feedbackUpdatesTheNewestTurn() {
        AiInteractionLog newest = AiInteractionLog.builder().id(UUID.randomUUID()).build();
        when(repository.findByConversationIdAndUserIdOrderByCreatedAtDesc(
                any(UUID.class), any(UUID.class), any(Pageable.class))).thenReturn(List.of(newest));

        boolean recorded = logger.recordFeedback(conversationId, userId, "down", "  it sent me to the wrong page  ");

        assertThat(recorded).isTrue();
        assertThat(newest.getFeedbackRating()).isEqualTo("DOWN");
        assertThat(newest.getFeedbackComment()).isEqualTo("it sent me to the wrong page");
        assertThat(newest.getFeedbackAt()).isNotNull();
    }

    @Test
    @DisplayName("an unrecognised rating is ignored without touching the database")
    void badRatingsAreIgnored() {
        assertThat(logger.recordFeedback(conversationId, userId, "MAYBE", null)).isFalse();

        // Not an exception: the endpoint exists to be unobtrusive, and the DB CHECK would turn a
        // typo into a 500 on a path the user cannot retry usefully.
        verify(repository, never()).findByConversationIdAndUserIdOrderByCreatedAtDesc(
                any(UUID.class), any(UUID.class), any(Pageable.class));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("feedback on a conversation the caller does not own changes nothing")
    void feedbackIsOwnerScoped() {
        when(repository.findByConversationIdAndUserIdOrderByCreatedAtDesc(
                any(UUID.class), any(UUID.class), any(Pageable.class))).thenReturn(List.of());

        assertThat(logger.recordFeedback(conversationId, UUID.randomUUID(), "UP", null)).isFalse();
        verify(repository, never()).save(any());
    }

    private RetrievalResult result(String knowledgeId, double score) {
        return RetrievalResult.builder()
                .knowledgeId(knowledgeId).chunkOrdinal(0).type(KnowledgeType.ACTION)
                .module("leave").pageId("leave").sourceRef("yaml:test")
                .title(knowledgeId).body("body").score(score).build();
    }
}
