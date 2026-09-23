package com.nforce.onehr.ai.contract;

/**
 * The seam that keeps OneHR from being wired to one vendor. Mistral is the first implementation;
 * swapping provider must mean adding a class here and changing {@code app.ai.provider}, never
 * touching assistant business logic.
 *
 * <p>Implementations own transport, auth, retries and timeouts, and must translate every failure
 * into {@code AiProviderException}. They must not interpret, validate or repair the model's
 * content — that belongs to {@code ResponseValidator}, which has to run identically for every
 * provider.
 */
public interface LlmProvider {

    /** Stable identifier recorded in the interaction log, e.g. {@code "mistral"}. */
    String name();

    /** @throws com.nforce.onehr.ai.exception.AiProviderException on any failure */
    LlmCompletion complete(LlmRequest request);

    /**
     * Real Mistral HTTP attempts for the most recent {@link #complete} call on this thread, success
     * or failure — read on the failure path (where no {@link LlmCompletion} exists to carry it) so
     * {@code AiAssistantService} can still count the real requests a failed completion cost.
     */
    int lastAttemptCount();
}
