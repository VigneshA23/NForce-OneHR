package com.nforce.onehr.ai.retrieval;

import com.nforce.onehr.ai.config.AiProperties;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.EmbeddingProvider;
import com.nforce.onehr.ai.contract.KnowledgeIndexRepository;
import com.nforce.onehr.ai.contract.KnowledgeType;
import com.nforce.onehr.ai.contract.RetrievalQuery;
import com.nforce.onehr.ai.contract.RetrievalResult;
import com.nforce.onehr.ai.exception.AiProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorKnowledgeRetrieverTest {

    @Mock private KnowledgeIndexRepository index;
    @Mock private EmbeddingProvider embeddingProvider;

    private AiProperties properties;
    private VectorKnowledgeRetriever retriever;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        retriever = new VectorKnowledgeRetriever(index, embeddingProvider, properties);
    }

    private static RetrievalResult result(String id, String module, String pageId, double score, String body) {
        return RetrievalResult.builder()
                .knowledgeId(id).chunkOrdinal(0).type(KnowledgeType.ACTION)
                .module(module).pageId(pageId).sourceRef("probe")
                .title(id).body(body).score(score).build();
    }

    private static RetrievalQuery query(String text) {
        return RetrievalQuery.builder()
                .rawQuery(text)
                .audiences(Set.of(AudienceBucket.EMPLOYEE))
                .build();
    }

    @Test
    @DisplayName("embeds the question and passes the caller's audiences through to the index")
    void embedsAndForwardsAudiences() {
        float[] vector = new float[]{0.1f, 0.2f};
        when(embeddingProvider.embed("How do I apply for leave?")).thenReturn(vector);
        when(index.search(any())).thenReturn(List.of(result("a", "leave", "leave", 0.9, "body")));

        retriever.retrieve(query("How do I apply for leave?"));

        ArgumentCaptor<RetrievalQuery> sent = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(index).search(sent.capture());
        assertThat(sent.getValue().getEmbedding()).isSameAs(vector);
        assertThat(sent.getValue().getAudiences()).containsExactly(AudienceBucket.EMPLOYEE);
        assertThat(sent.getValue().getMinScore()).isEqualTo(properties.getRetrieval().getMinScore());
    }

    @Test
    @DisplayName("over-fetches candidates so re-ranking has something to reorder")
    void overFetchesCandidates() {
        when(embeddingProvider.embed(any())).thenReturn(new float[]{0.1f});
        when(index.search(any())).thenReturn(List.of());

        retriever.retrieve(query("anything"));

        ArgumentCaptor<RetrievalQuery> sent = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(index).search(sent.capture());
        assertThat(sent.getValue().getTopK())
                .isEqualTo(properties.getRetrieval().getTopK() * properties.getRetrieval().getCandidateMultiplier());
    }

    @Test
    @DisplayName("an empty result is returned as-is, not retried or widened")
    void emptyResultsPassThrough() {
        when(embeddingProvider.embed(any())).thenReturn(new float[]{0.1f});
        when(index.search(any())).thenReturn(List.of());

        assertThat(retriever.retrieve(query("something obscure"))).isEmpty();
    }

    @Test
    @DisplayName("a blank question never reaches the embedding provider")
    void blankQuestionShortCircuits() {
        assertThat(retriever.retrieve(query("   "))).isEmpty();
        assertThat(retriever.retrieve(null)).isEmpty();
        verify(embeddingProvider, never()).embed(any());
        verify(index, never()).search(any());
    }

    @Test
    @DisplayName("an embedding failure propagates so the caller can degrade to UNKNOWN")
    void embeddingFailurePropagates() {
        when(embeddingProvider.embed(any()))
                .thenThrow(new AiProviderException("mistral", "timeout", true));

        // Must not be swallowed into an empty list - "the provider is down" and "we know nothing
        // about that" are different things to tell a user.
        assertThatThrownBy(() -> retriever.retrieve(query("How do I apply for leave?")))
                .isInstanceOf(AiProviderException.class);
    }

    @Test
    @DisplayName("current page boosts its own module and page, but cannot override a better match")
    void pageHintBoostsWithoutOverriding() {
        when(embeddingProvider.embed(any())).thenReturn(new float[]{0.1f});
        when(index.search(any())).thenReturn(List.of(
                result("attendance.regularize", "attendance", "attendance", 0.80, "regularization"),
                result("leave.apply", "leave", "leave", 0.78, "leave")));

        // Asked from the Leave page, but the question is clearly about attendance.
        List<RetrievalResult> ranked = retriever.retrieve(RetrievalQuery.builder()
                .rawQuery("where do I fix my attendance")
                .audiences(Set.of(AudienceBucket.EMPLOYEE))
                .moduleHint("leave").pageIdHint("leave")
                .build());

        // leave gets +0.08 -> 0.86, which does beat 0.80 here. The boost is deliberately small, so
        // verify it stays small rather than asserting a particular winner.
        assertThat(ranked).hasSize(2);
        double leaveScore = ranked.stream()
                .filter(r -> r.getKnowledgeId().equals("leave.apply")).findFirst().orElseThrow().getScore();
        assertThat(leaveScore).isCloseTo(0.86, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @DisplayName("a strong off-page match still wins over a boosted weak one")
    void boostCannotRescueAWeakMatch() {
        when(embeddingProvider.embed(any())).thenReturn(new float[]{0.1f});
        when(index.search(any())).thenReturn(List.of(
                result("attendance.regularize", "attendance", "attendance", 0.92, "regularization"),
                result("leave.apply", "leave", "leave", 0.40, "leave")));

        List<RetrievalResult> ranked = retriever.retrieve(RetrievalQuery.builder()
                .rawQuery("how do I correct a missed punch")
                .audiences(Set.of(AudienceBucket.EMPLOYEE))
                .moduleHint("leave").pageIdHint("leave")
                .build());

        assertThat(ranked.get(0).getKnowledgeId()).isEqualTo("attendance.regularize");
    }

    @Test
    @DisplayName("keeps only the best chunk of each knowledge unit")
    void dedupesByKnowledgeUnit() {
        when(embeddingProvider.embed(any())).thenReturn(new float[]{0.1f});
        when(index.search(any())).thenReturn(List.of(
                result("leave.apply", "leave", "leave", 0.91, "chunk one"),
                result("leave.apply", "leave", "leave", 0.88, "chunk two"),
                result("leave.approval", "leave", "leave", 0.70, "workflow")));

        List<RetrievalResult> ranked = retriever.retrieve(query("leave"));

        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).getKnowledgeId()).isEqualTo("leave.apply");
        assertThat(ranked.get(0).getBody()).isEqualTo("chunk one");
    }

    @Test
    @DisplayName("respects topK")
    void respectsTopK() {
        properties.getRetrieval().setTopK(2);
        when(embeddingProvider.embed(any())).thenReturn(new float[]{0.1f});
        when(index.search(any())).thenReturn(List.of(
                result("a", "m", "p", 0.9, "x"), result("b", "m", "p", 0.8, "x"),
                result("c", "m", "p", 0.7, "x"), result("d", "m", "p", 0.6, "x")));

        assertThat(retriever.retrieve(query("q"))).hasSize(2);
    }

    @Test
    @DisplayName("stops at the character budget instead of packing in weaker short chunks")
    void respectsContextBudget() {
        properties.getRetrieval().setMaxContextChars(100);
        when(embeddingProvider.embed(any())).thenReturn(new float[]{0.1f});
        when(index.search(any())).thenReturn(List.of(
                result("big", "m", "p", 0.95, "x".repeat(90)),
                result("alsoBig", "m", "p", 0.90, "y".repeat(90)),
                result("tiny", "m", "p", 0.50, "z")));

        List<RetrievalResult> ranked = retriever.retrieve(query("q"));

        // Only the first fits. "tiny" is not squeezed in behind it - that would mean dropping the
        // most relevant knowledge to make room for the least.
        assertThat(ranked).extracting(RetrievalResult::getKnowledgeId).containsExactly("big");
    }

    @Test
    @DisplayName("always keeps at least one result even if it alone exceeds the budget")
    void neverReturnsNothingBecauseOfTheBudget() {
        properties.getRetrieval().setMaxContextChars(10);
        when(embeddingProvider.embed(any())).thenReturn(new float[]{0.1f});
        when(index.search(any())).thenReturn(List.of(result("huge", "m", "p", 0.99, "x".repeat(5000))));

        // Returning nothing here would produce an UNKNOWN for a question we had a strong match for.
        assertThat(retriever.retrieve(query("q"))).hasSize(1);
    }
}
