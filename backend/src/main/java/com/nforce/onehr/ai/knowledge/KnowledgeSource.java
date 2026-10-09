package com.nforce.onehr.ai.knowledge;

import com.nforce.onehr.ai.contract.KnowledgeDocument;

import java.util.List;

/**
 * Somewhere knowledge comes from.
 *
 * <p>Two implementations exist: authored YAML in the repository, and published Help and Guidance
 * rows. The interface is what keeps them interchangeable, so a future DB-backed authoring UI can be
 * added as a third without retrieval, chunking or indexing knowing anything changed.
 *
 * <p>Implementations are responsible for setting a {@code sourceRef} prefix unique to themselves —
 * that prefix is how a re-index retires units whose source disappeared entirely, which per-unit
 * upserts cannot detect.
 */
public interface KnowledgeSource {

    /** Stable name, used in logs and in the indexing summary. */
    String name();

    /**
     * The {@code sourceRef} prefix every document from this source carries, e.g. {@code yaml:} or
     * {@code help_content:}. Must not overlap another source, or a re-index of one would delete
     * the other's chunks.
     */
    String sourceRefPrefix();

    /** Everything currently defined by this source. Never null. */
    List<KnowledgeDocument> load();
}
