package com.nforce.onehr.ai.provider.mistral;

import com.fasterxml.jackson.databind.JsonNode;
import com.nforce.onehr.ai.config.AiProperties;
import com.nforce.onehr.ai.contract.EmbeddingProvider;
import com.nforce.onehr.ai.exception.AiProviderException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mistral implementation of {@link EmbeddingProvider}, backed by {@code mistral-embed}.
 *
 * <p>{@link #dimensions()} is a hard 1024 and is checked against the {@code vector(1024)} column
 * width at startup. A silent mismatch is the worst outcome available here: inserts would fail at
 * index time, or worse, a re-index against a differently-sized model would make every stored
 * embedding meaningless while the application carried on looking healthy.
 */
@Component
@RequiredArgsConstructor
public class MistralEmbeddingProvider implements EmbeddingProvider {

    private static final String EMBEDDINGS_PATH = "/v1/embeddings";

    /** mistral-embed is fixed at 1024 dimensions. */
    private static final int DIMENSIONS = 1024;

    /**
     * Mistral accepts batches, but very large ones risk request-size limits and make a single
     * failure expensive to retry. Indexing is not latency-sensitive, so modest batches are the
     * better trade.
     */
    private static final int MAX_BATCH = 32;

    private final MistralHttpClient httpClient;
    private final AiProperties properties;

    @Override
    public String name() {
        return "mistral";
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public float[] embed(String text) {
        List<float[]> result = embedBatch(List.of(text));
        if (result.isEmpty()) {
            throw new AiProviderException("mistral", "Embedding request returned no vectors", false);
        }
        return result.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();

        List<float[]> all = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += MAX_BATCH) {
            List<String> batch = texts.subList(start, Math.min(start + MAX_BATCH, texts.size()));
            all.addAll(embedOneBatch(batch));
        }
        return all;
    }

    private List<float[]> embedOneBatch(List<String> batch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getMistral().getEmbedModel());
        body.put("input", batch);

        JsonNode response = httpClient.postJson(EMBEDDINGS_PATH, body);
        JsonNode data = response.path("data");
        if (!data.isArray() || data.size() != batch.size()) {
            throw new AiProviderException("mistral",
                    "Expected " + batch.size() + " embeddings, got " + (data.isArray() ? data.size() : 0),
                    false);
        }

        // Ordered by the index the API returns rather than array position. They normally agree, but
        // relying on that would silently pair every chunk with the wrong vector if they ever did
        // not - a corruption that produces plausible-looking nonsense rather than an error.
        float[][] ordered = new float[batch.size()][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= batch.size()) {
                throw new AiProviderException("mistral", "Embedding response had an out-of-range index", false);
            }
            ordered[index] = toVector(item.path("embedding"));
        }
        for (int i = 0; i < ordered.length; i++) {
            if (ordered[i] == null) {
                throw new AiProviderException("mistral", "Embedding response was missing index " + i, false);
            }
        }
        return List.of(ordered);
    }

    private float[] toVector(JsonNode embedding) {
        if (!embedding.isArray()) {
            throw new AiProviderException("mistral", "Embedding was not an array", false);
        }
        if (embedding.size() != DIMENSIONS) {
            throw new AiProviderException("mistral",
                    "Embedding width " + embedding.size() + " does not match the expected " + DIMENSIONS
                            + " - the configured embed model does not match the vector column", false);
        }
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) embedding.get(i).asDouble();
        }
        return vector;
    }
}
