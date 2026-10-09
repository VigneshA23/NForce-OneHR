package com.nforce.onehr.ai.contract;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

/** A single authorised retrieval request. */
@Data
@Builder
public class RetrievalQuery {

    /** The user's question, already length-capped. Embedded, never interpolated into SQL. */
    private String rawQuery;

    /** Embedding of {@link #rawQuery}. */
    private float[] embedding;

    /**
     * The caller's audience buckets. Applied as a SQL predicate inside the kNN query, not as a
     * post-filter in Java — post-filtering would let unauthorised chunks consume top-k slots and
     * quietly starve the answer of the content the user is actually allowed to see.
     */
    private Set<AudienceBucket> audiences;

    /** Optional module bias from the user's current page. Boosts ranking; never restricts. */
    private String moduleHint;

    /** Optional page bias from the user's current page. Boosts ranking; never restricts. */
    private String pageIdHint;

    private int topK;

    /** Cosine-similarity floor. Results below this are dropped before the LLM is ever called. */
    private double minScore;
}
