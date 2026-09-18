package com.nforce.onehr.ai.provider.mistral;

import com.fasterxml.jackson.databind.JsonNode;
import com.nforce.onehr.ai.config.AiProperties;
import com.nforce.onehr.ai.contract.LlmCompletion;
import com.nforce.onehr.ai.contract.LlmProvider;
import com.nforce.onehr.ai.contract.LlmRequest;
import com.nforce.onehr.ai.exception.AiProviderException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mistral implementation of {@link LlmProvider}.
 *
 * <p>Everything Mistral-specific stops here: the endpoint, the message array shape, the
 * {@code response_format} flag and the usage field names. Swapping provider means adding a sibling
 * of this class and changing {@code app.ai.provider} - no assistant logic moves.
 *
 * <p>Deliberately does not inspect, repair or validate the model's content. That belongs to
 * {@code ResponseValidator}, which has to behave identically no matter which provider produced the
 * text; a provider that quietly "fixed up" its own output would make that impossible to reason about.
 */
@Component
@RequiredArgsConstructor
public class MistralLlmProvider implements LlmProvider {

    private static final String CHAT_PATH = "/v1/chat/completions";

    private final MistralHttpClient httpClient;
    private final AiProperties properties;

    @Override
    public String name() {
        return "mistral";
    }

    @Override
    public LlmCompletion complete(LlmRequest request) {
        AiProperties.Mistral cfg = properties.getMistral();

        List<Map<String, String>> messages = new ArrayList<>();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.getSystemPrompt()));
        }
        messages.add(Map.of("role", "user", "content", request.getUserPrompt()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getChatModel());
        body.put("messages", messages);
        body.put("temperature", request.getTemperature() > 0 ? request.getTemperature() : cfg.getTemperature());
        body.put("max_tokens", request.getMaxTokens() > 0 ? request.getMaxTokens() : cfg.getMaxTokens());
        if (request.isJsonMode()) {
            // Raises the odds of clean JSON but is not relied on: ResponseValidator still strips
            // fences and surrounding prose, because this flag is a provider nicety and the
            // validator has to hold for providers that lack it.
            body.put("response_format", Map.of("type", "json_object"));
        }

        long started = System.currentTimeMillis();
        JsonNode response = httpClient.postJson(CHAT_PATH, body);
        long latency = System.currentTimeMillis() - started;

        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new AiProviderException("mistral", "Mistral response contained no choices", false);
        }
        String content = choices.get(0).path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            throw new AiProviderException("mistral", "Mistral returned an empty completion", false);
        }

        JsonNode usage = response.path("usage");
        return LlmCompletion.builder()
                .content(content)
                .provider(name())
                .model(response.path("model").asText(cfg.getChatModel()))
                .promptTokens(usage.path("prompt_tokens").asInt(0))
                .completionTokens(usage.path("completion_tokens").asInt(0))
                .latencyMs(latency)
                .build();
    }
}
