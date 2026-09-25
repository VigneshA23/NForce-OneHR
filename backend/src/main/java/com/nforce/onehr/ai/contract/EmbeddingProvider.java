package com.nforce.onehr.ai.contract;

import java.util.List;

/**
 * Text-to-vector seam, kept separate from {@link LlmProvider} so the chat model and the embedding
 * model can be changed independently — re-embedding is a full re-index, swapping the chat model is
 * a config change, and tying them together would force the expensive one on every occurrence of
 * the cheap one.
 */
public interface EmbeddingProvider {

    String name();

    /**
     * Vector width. Must match the {@code vector(n)} column width in the schema; a mismatch is a
     * startup-time misconfiguration, not something to discover on the first query.
     */
    int dimensions();

    /** @throws com.nforce.onehr.ai.exception.AiProviderException on any failure */
    float[] embed(String text);

    /**
     * Batch form used by indexing. Returns one vector per input, in order.
     *
     * @throws com.nforce.onehr.ai.exception.AiProviderException on any failure
     */
    List<float[]> embedBatch(List<String> texts);

    /**
     * Real Mistral HTTP attempts and prompt-token usage for the most recent {@link #embed(String)}
     * call on this thread, success or failure — an embedding call is never itself logged as its own
     * turn, so this is how {@code AiAssistantService} attaches its real cost to the same
     * {@code AiInteractionLog} row as the completion call it accompanied. {@code attempts == 0}
     * means no call has been made yet on this thread. Not meaningful after {@link #embedBatch}
     * (indexing) — only reflects that call's own last sub-batch, which nothing reads.
     */
    EmbeddingCallInfo lastCallInfo();

    record EmbeddingCallInfo(int attempts, int promptTokens) {
        public static final EmbeddingCallInfo NONE = new EmbeddingCallInfo(0, 0);
    }
}
