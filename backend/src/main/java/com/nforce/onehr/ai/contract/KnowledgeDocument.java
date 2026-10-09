package com.nforce.onehr.ai.contract;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One authored knowledge unit, before chunking — the in-memory form of a YAML file under
 * {@code ai-knowledge/}, or of a published Help &amp; Guidance row.
 *
 * <p>Provider-neutral and storage-neutral by design: nothing here knows about embeddings, vectors
 * or pgvector. {@code KnowledgeChunker} turns these into {@link KnowledgeChunk}s, and only then
 * does an {@link EmbeddingProvider} get involved.
 */
@Data
@Builder
public class KnowledgeDocument {

    /** Stable authored id, unique across the whole knowledge base, e.g. {@code action.leave.apply}. */
    private String knowledgeId;

    private KnowledgeType type;

    /** Owning module id. */
    private String module;

    /** Related page, where the unit is page-specific. Validated against the page registry. */
    private String pageId;

    /** Related action id, for ACTION units. */
    private String actionId;

    /** Related workflow id, for WORKFLOW units. */
    private String workflowId;

    /** Bumped by the author when the meaning changes; carried onto every chunk for traceability. */
    private int version;

    /**
     * Who may retrieve this. Fail-closed: the schema validator requires at least one audience, so
     * an un-tagged unit is a build failure rather than something silently visible to everyone.
     * (This deliberately differs from {@code help_content_audience}, where no rows means
     * "everyone" — that legacy default is applied when ingesting help content, not here.)
     */
    private Set<AudienceBucket> audiences;

    /** Where this came from: a YAML path, or {@code help_content:<uuid>}. */
    private String sourceRef;

    private String title;

    /** The prose body that gets embedded and shown to the model. */
    private String body;

    /** Alternate phrasings that should retrieve this unit. Appended to the embedded text. */
    private List<String> synonyms;

    /** Free-form extra fields (roles, preconditions, validations…) persisted as JSONB. */
    private Map<String, Object> metadata;
}
