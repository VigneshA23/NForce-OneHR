package com.nforce.onehr.ai.contract;

import lombok.Builder;
import lombok.Data;

/** A provider-neutral completion result, including the figures observability needs. */
@Data
@Builder
public class LlmCompletion {

    /** Raw text returned by the model. Still untrusted — must go through ResponseValidator. */
    private String content;

    private String provider;

    private String model;

    private int promptTokens;

    private int completionTokens;

    private long latencyMs;
}
