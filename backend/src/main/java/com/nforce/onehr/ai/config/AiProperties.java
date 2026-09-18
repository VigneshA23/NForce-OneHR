package com.nforce.onehr.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * All AI assistant configuration, under {@code app.ai}.
 *
 * <p>Follows the existing typed-config convention in this codebase ({@code AttendanceProperties},
 * {@code EmployeeCreationLockProperties}) rather than scattering {@code @Value} lookups.
 *
 * <p>{@link #enabled} defaults to <strong>false</strong> on purpose. OneHR must start normally in
 * an environment that has no Mistral key at all - a missing AI credential is not a reason for the
 * HR system to fail to boot - so the feature opts in rather than out.
 */
@Component
@ConfigurationProperties(prefix = "app.ai")
@Data
public class AiProperties {

    /** Master switch. False means the endpoints report unavailable and the UI hides the launcher. */
    private boolean enabled = false;

    /** Which {@code LlmProvider}/{@code EmbeddingProvider} pair to use. */
    private String provider = "mistral";

    private Mistral mistral = new Mistral();
    private Retrieval retrieval = new Retrieval();
    private Limits limits = new Limits();

    @Data
    public static class Mistral {
        /** Never has a default: an unset key must fail loudly, not silently call an unauthenticated API. */
        private String apiKey;
        private String baseUrl = "https://api.mistral.ai";
        private String chatModel = "ministral-8b-latest";
        /** 1024 dimensions, matching the vector(1024) column in V185. */
        private String embedModel = "mistral-embed";
        private int timeoutSeconds = 30;
        private int connectTimeoutSeconds = 5;
        /** Total attempts including the first. Only 429 and 5xx are retried. */
        private int maxAttempts = 3;
        private long retryBackoffMillis = 500;
        /** Low: the assistant reports documented behaviour, it does not compose freely. */
        private double temperature = 0.2;
        private int maxTokens = 1200;
    }

    @Data
    public static class Retrieval {
        private int topK = 8;
        /**
         * Cosine floor, calibrated against real mistral-embed vectors rather than theory.
         *
         * <p>An earlier value of 0.35 came from synthetic orthogonal vectors, where unrelated text
         * scores 0.0. Real sentence embeddings do not behave that way: measured against the actual
         * knowledge base, "what is the capital of France" still scored <strong>0.594</strong>
         * against every OneHR document, because natural-language embeddings share a high baseline
         * similarity. A 0.35 floor would have let every out-of-scope question through.
         *
         * <p>Measured distribution: a direct hit scores 0.90 ("how do I apply for leave" against
         * the leave action), a good informal match 0.68-0.79, a weak-but-genuine match 0.62, and
         * unrelated text about 0.59.
         *
         * <p>0.60 sits just above the noise floor. It is deliberately not higher: pushing it to
         * 0.65 to catch near-miss junk would also drop genuine weak matches like "who approves my
         * stuff" at 0.624. This threshold only filters the clearly unrelated, cheaply and without
         * an LLM call. Questions that land in the ambiguous band are meant to reach the model,
         * where the grounding instructions and the UNKNOWN path decide - that is what they are for.
         */
        private double minScore = 0.60;
        /** Candidates fetched before module/page re-ranking trims back to topK. */
        private int candidateMultiplier = 3;
        /** Guards the prompt against a handful of long chunks crowding out everything else. */
        private int maxContextChars = 12000;
    }

    @Data
    public static class Limits {
        private int maxMessageChars = 1000;
        private int maxRequestsPerUserPerHour = 60;
        /** Prior exchanges replayed to the model. Bounded to keep token growth predictable. */
        private int maxHistoryTurns = 6;
    }
}
