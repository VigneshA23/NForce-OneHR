package com.nforce.onehr.ai.contract;

import lombok.Builder;
import lombok.Data;

/**
 * One scored, already-authorised chunk. Anything reaching this type has passed the audience
 * predicate, so downstream code never re-checks visibility.
 */
@Data
@Builder
public class RetrievalResult {

    private String knowledgeId;

    private int chunkOrdinal;

    private KnowledgeType type;

    private String module;

    private String pageId;

    /** Provenance, preserved so an answer can be traced back internally. */
    private String sourceRef;

    private String title;

    private String body;

    /** Cosine similarity in [0,1]; higher is closer. */
    private double score;
}
