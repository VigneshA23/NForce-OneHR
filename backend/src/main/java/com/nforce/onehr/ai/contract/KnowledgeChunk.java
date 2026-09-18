package com.nforce.onehr.ai.contract;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.Set;

/**
 * One indexable unit: what actually gets embedded and written to {@code ai_knowledge_chunk}.
 *
 * <p>Chunking is one chunk per meaningful application concept (an action, a workflow stage), never
 * a fixed token window — a half-sentence of an action's preconditions retrieved on its own would
 * be worse than not retrieving it.
 *
 * <p>Note this is a plain contract object, <strong>not</strong> a JPA entity, and there is no
 * entity anywhere for {@code ai_knowledge_chunk}. That is deliberate: the backend test profile runs
 * Hibernate {@code ddl-auto: create-drop} against H2 with Flyway disabled, so a mapped
 * {@code vector(1024)} column would fail schema generation and break {@code contextLoads()} along
 * with every other {@code @SpringBootTest}. All access goes through JdbcTemplate instead — see
 * {@code PgVectorKnowledgeIndexRepository}.
 */
@Data
@Builder
public class KnowledgeChunk {

    /** Id of the {@link KnowledgeDocument} this came from. */
    private String knowledgeId;

    /** 0-based position within that document. {@code (knowledgeId, chunkOrdinal)} is unique. */
    private int chunkOrdinal;

    private KnowledgeType type;

    private String module;

    private String pageId;

    private String sourceRef;

    private int version;

    /**
     * SHA-256 of the embedded text. Lets a re-index skip chunks whose content is unchanged rather
     * than paying for an embedding call per unit on every run.
     */
    private String contentHash;

    private String title;

    /** The exact text that was embedded and that will be shown to the model. */
    private String body;

    private Map<String, Object> metadata;

    private Set<AudienceBucket> audiences;

    /** Null until an {@link EmbeddingProvider} has run. Non-null is required to persist. */
    private float[] embedding;
}
