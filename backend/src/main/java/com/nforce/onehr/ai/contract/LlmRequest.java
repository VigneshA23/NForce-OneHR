package com.nforce.onehr.ai.contract;

import lombok.Builder;
import lombok.Data;

/** A provider-neutral completion request. Carries no Mistral/OpenAI/etc. concepts. */
@Data
@Builder
public class LlmRequest {

    /** Policy, role context and the fenced knowledge block. */
    private String systemPrompt;

    /** Bounded conversation history plus the current question. */
    private String userPrompt;

    private int maxTokens;

    /** Low by default — this assistant reports documented behaviour, it does not compose freely. */
    private double temperature;

    /** Ask the provider for strict JSON where it supports it. Never a substitute for validation. */
    private boolean jsonMode;
}
