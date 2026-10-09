package com.nforce.onehr.ai.provider.mistral;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.ai.config.AiProperties;
import com.nforce.onehr.ai.contract.LlmCompletion;
import com.nforce.onehr.ai.contract.LlmRequest;
import com.nforce.onehr.ai.exception.AiProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Covers how the adapters read Mistral's payloads and how they fail.
 *
 * <p>Transport is mocked rather than stubbed over HTTP: the endpoint shapes these tests assume
 * (1024-dimension embeddings, the {@code index} field, {@code choices[0].message.content}, the
 * {@code usage} counters) were verified against the live API separately, so what is worth covering
 * here is the parsing and the failure modes - especially the ones that would otherwise corrupt the
 * index silently rather than throw.
 */
@ExtendWith(MockitoExtension.class)
class MistralProviderTest {

    @Mock private MistralHttpClient httpClient;

    private final ObjectMapper mapper = new ObjectMapper();
    private AiProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        properties.getMistral().setApiKey("test-key");
    }

    private com.fasterxml.jackson.databind.JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    private String embeddingJson(int count, int dims) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"index\":").append(i).append(",\"embedding\":[");
            for (int d = 0; d < dims; d++) { if (d > 0) sb.append(','); sb.append("0.01"); }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    @Nested
    @DisplayName("chat completions")
    class Chat {

        @Test
        void extractsContentModelAndTokenCounts() throws Exception {
            when(httpClient.postJson(eq("/v1/chat/completions"), any())).thenReturn(json("""
                    {"model":"ministral-8b-latest",
                     "choices":[{"message":{"role":"assistant","content":"{\\"type\\":\\"HOW_TO\\"}"}}],
                     "usage":{"prompt_tokens":1200,"completion_tokens":85}}
                    """));

            MistralLlmProvider provider = new MistralLlmProvider(httpClient, properties);
            LlmCompletion c = provider.complete(LlmRequest.builder()
                    .systemPrompt("policy").userPrompt("How do I apply for leave?").jsonMode(true).build());

            assertThat(c.getContent()).isEqualTo("{\"type\":\"HOW_TO\"}");
            assertThat(c.getProvider()).isEqualTo("mistral");
            assertThat(c.getModel()).isEqualTo("ministral-8b-latest");
            assertThat(c.getPromptTokens()).isEqualTo(1200);
            assertThat(c.getCompletionTokens()).isEqualTo(85);
            assertThat(c.getLatencyMs()).isGreaterThanOrEqualTo(0);
        }

        @Test
        void sendsSystemAndUserMessagesAndRequestsJsonMode() throws Exception {
            when(httpClient.postJson(any(), any())).thenReturn(json(
                    "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}"));

            new MistralLlmProvider(httpClient, properties).complete(LlmRequest.builder()
                    .systemPrompt("POLICY TEXT").userPrompt("QUESTION").jsonMode(true).build());

            ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
            org.mockito.Mockito.verify(httpClient).postJson(eq("/v1/chat/completions"), body.capture());

            @SuppressWarnings("unchecked")
            Map<String, Object> sent = (Map<String, Object>) body.getValue();
            @SuppressWarnings("unchecked")
            List<Map<String, String>> messages = (List<Map<String, String>>) sent.get("messages");

            assertThat(messages).hasSize(2);
            assertThat(messages.get(0)).containsEntry("role", "system").containsEntry("content", "POLICY TEXT");
            assertThat(messages.get(1)).containsEntry("role", "user").containsEntry("content", "QUESTION");
            assertThat(sent).containsKey("response_format");
            assertThat(sent).containsEntry("model", "ministral-8b-latest");
        }

        @Test
        void omitsTheSystemMessageWhenThereIsNoPolicy() throws Exception {
            when(httpClient.postJson(any(), any())).thenReturn(json(
                    "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}"));

            new MistralLlmProvider(httpClient, properties)
                    .complete(LlmRequest.builder().userPrompt("Q").build());

            ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
            org.mockito.Mockito.verify(httpClient).postJson(any(), body.capture());
            @SuppressWarnings("unchecked")
            Map<String, Object> sent = (Map<String, Object>) body.getValue();
            @SuppressWarnings("unchecked")
            List<Map<String, String>> messages = (List<Map<String, String>>) sent.get("messages");

            assertThat(messages).hasSize(1);
            assertThat(messages.get(0)).containsEntry("role", "user");
        }

        @Test
        void emptyChoicesOrBlankContentFailLoudly() throws Exception {
            MistralLlmProvider provider = new MistralLlmProvider(httpClient, properties);
            LlmRequest req = LlmRequest.builder().userPrompt("Q").build();

            when(httpClient.postJson(any(), any())).thenReturn(json("{\"choices\":[]}"));
            assertThatThrownBy(() -> provider.complete(req))
                    .isInstanceOf(AiProviderException.class).hasMessageContaining("no choices");

            when(httpClient.postJson(any(), any()))
                    .thenReturn(json("{\"choices\":[{\"message\":{\"content\":\"  \"}}]}"));
            assertThatThrownBy(() -> provider.complete(req))
                    .isInstanceOf(AiProviderException.class).hasMessageContaining("empty completion");
        }

        @Test
        void doesNotInterpretOrRepairModelContent() throws Exception {
            // Validation belongs to ResponseValidator so it behaves identically for every provider.
            // A provider that tidied its own output would make that impossible to reason about.
            when(httpClient.postJson(any(), any())).thenReturn(json(
                    "{\"choices\":[{\"message\":{\"content\":\"```json\\n{\\\"type\\\":\\\"NONSENSE\\\"}\\n```\"}}]}"));

            LlmCompletion c = new MistralLlmProvider(httpClient, properties)
                    .complete(LlmRequest.builder().userPrompt("Q").build());

            assertThat(c.getContent()).startsWith("```json").contains("NONSENSE");
        }
    }

    @Nested
    @DisplayName("embeddings")
    class Embeddings {

        @Test
        void reportsTheWidthTheVectorColumnExpects() {
            assertThat(new MistralEmbeddingProvider(httpClient, properties).dimensions()).isEqualTo(1024);
        }

        @Test
        void parsesABatchInIndexOrder() throws Exception {
            when(httpClient.postJson(eq("/v1/embeddings"), any())).thenReturn(json(embeddingJson(3, 1024)));

            List<float[]> vectors = new MistralEmbeddingProvider(httpClient, properties)
                    .embedBatch(List.of("a", "b", "c"));

            assertThat(vectors).hasSize(3);
            assertThat(vectors.get(0)).hasSize(1024);
        }

        @Test
        void reordersByTheReturnedIndexNotArrayPosition() throws Exception {
            // If these ever disagree, trusting position would pair every chunk with the wrong
            // vector - corruption that reads as plausible nonsense instead of failing.
            String shuffled = "{\"data\":[" +
                    "{\"index\":1,\"embedding\":[" + "0.9,".repeat(1023) + "0.9]}," +
                    "{\"index\":0,\"embedding\":[" + "0.1,".repeat(1023) + "0.1]}]}";
            when(httpClient.postJson(any(), any())).thenReturn(json(shuffled));

            List<float[]> vectors = new MistralEmbeddingProvider(httpClient, properties)
                    .embedBatch(List.of("first", "second"));

            assertThat(vectors.get(0)[0]).isEqualTo(0.1f);
            assertThat(vectors.get(1)[0]).isEqualTo(0.9f);
        }

        @Test
        void aWidthMismatchFailsRatherThanIndexingGarbage() throws Exception {
            // The worst available outcome is a silently wrong-width model: every stored embedding
            // becomes meaningless while the application still looks healthy.
            when(httpClient.postJson(any(), any())).thenReturn(json(embeddingJson(1, 512)));

            assertThatThrownBy(() -> new MistralEmbeddingProvider(httpClient, properties).embed("x"))
                    .isInstanceOf(AiProviderException.class)
                    .hasMessageContaining("512")
                    .hasMessageContaining("does not match");
        }

        @Test
        void aShortOrMisindexedResponseFails() throws Exception {
            MistralEmbeddingProvider provider = new MistralEmbeddingProvider(httpClient, properties);

            when(httpClient.postJson(any(), any())).thenReturn(json(embeddingJson(1, 1024)));
            assertThatThrownBy(() -> provider.embedBatch(List.of("a", "b")))
                    .isInstanceOf(AiProviderException.class).hasMessageContaining("Expected 2");

            when(httpClient.postJson(any(), any())).thenReturn(json(
                    "{\"data\":[{\"index\":7,\"embedding\":[" + "0.1,".repeat(1023) + "0.1]}]}"));
            assertThatThrownBy(() -> provider.embed("a"))
                    .isInstanceOf(AiProviderException.class).hasMessageContaining("out-of-range");
        }

        @Test
        void emptyInputDoesNotCallTheApi() {
            assertThat(new MistralEmbeddingProvider(httpClient, properties).embedBatch(List.of())).isEmpty();
            org.mockito.Mockito.verifyNoInteractions(httpClient);
        }
    }
}
