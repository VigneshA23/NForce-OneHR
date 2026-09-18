package com.nforce.onehr.ai.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Whether the assistant is usable, for admins and for the frontend to decide whether to show the
 * launcher at all. Reports configuration and index state, never the API key.
 */
@Data
@Builder
public class AssistantHealthResponse {

    /** app.ai.enabled. False means the feature is switched off for this environment. */
    private boolean enabled;

    /** True when the knowledge index actually has content; false means re-index before use. */
    private boolean indexReady;

    private long indexedChunks;
    private Instant lastIndexedAt;
    private List<String> knowledgeSources;
    private String llmProvider;
    private String embeddingProvider;
    private int embeddingDimensions;
}
