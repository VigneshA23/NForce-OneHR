package com.nforce.onehr.ai.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.KnowledgeChunk;
import com.nforce.onehr.ai.contract.KnowledgeIndexRepository;
import com.nforce.onehr.ai.contract.KnowledgeType;
import com.nforce.onehr.ai.contract.RetrievalQuery;
import com.nforce.onehr.ai.contract.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * pgvector-backed knowledge index.
 *
 * <p><strong>Why JdbcTemplate and not JPA.</strong> There is deliberately no {@code @Entity} for
 * {@code ai_knowledge_chunk}, and adding one would break the build. The backend test profile runs
 * Hibernate with {@code ddl-auto: create-drop} against H2 with Flyway disabled
 * ({@code src/test/resources/application.yml}), so a mapped {@code vector(1024)} column would fail
 * H2 schema generation and take down {@code OneHrApplicationTests.contextLoads} plus every other
 * {@code @SpringBootTest}. Keeping the vector columns outside JPA entirely means the test profile
 * never has to know this table exists.
 *
 * <p><strong>Injection safety.</strong> Every value is bound as a parameter. The only SQL built
 * from Java is the {@code IN (?, ?, ...)} placeholder run for audiences, whose length comes from a
 * closed enum and whose values are still bound. Nothing the model or the user produces is ever
 * concatenated into a statement — the user's question reaches this class only as an embedding.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class PgVectorKnowledgeIndexRepository implements KnowledgeIndexRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private static final String UPSERT_CHUNK = """
            INSERT INTO ai_knowledge_chunk
                (knowledge_id, chunk_ordinal, knowledge_type, module, page_id, source_ref,
                 version, content_hash, title, body, metadata, embedding, indexed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector, now())
            ON CONFLICT (knowledge_id, chunk_ordinal) DO UPDATE SET
                knowledge_type = EXCLUDED.knowledge_type,
                module         = EXCLUDED.module,
                page_id        = EXCLUDED.page_id,
                source_ref     = EXCLUDED.source_ref,
                version        = EXCLUDED.version,
                content_hash   = EXCLUDED.content_hash,
                title          = EXCLUDED.title,
                body           = EXCLUDED.body,
                metadata       = EXCLUDED.metadata,
                embedding      = EXCLUDED.embedding,
                indexed_at     = now()
            RETURNING id
            """;

    @Override
    @Transactional
    public void upsert(KnowledgeChunk chunk) {
        requireEmbedding(chunk);

        UUID chunkId = jdbc.queryForObject(UPSERT_CHUNK, UUID.class,
                chunk.getKnowledgeId(),
                chunk.getChunkOrdinal(),
                chunk.getType() == null ? null : chunk.getType().name(),
                chunk.getModule(),
                chunk.getPageId(),
                chunk.getSourceRef(),
                chunk.getVersion(),
                chunk.getContentHash(),
                chunk.getTitle(),
                chunk.getBody(),
                writeMetadata(chunk.getMetadata()),
                toVectorLiteral(chunk.getEmbedding()));

        // Replace rather than merge: an audience removed from the source must actually disappear,
        // otherwise a unit narrowed from EMPLOYEE to HR would stay readable by everyone who could
        // read it before.
        jdbc.update("DELETE FROM ai_knowledge_chunk_audience WHERE chunk_id = ?", chunkId);
        for (AudienceBucket audience : safeAudiences(chunk)) {
            jdbc.update("INSERT INTO ai_knowledge_chunk_audience (chunk_id, audience) VALUES (?, ?)",
                    chunkId, audience.name());
        }
    }

    @Override
    @Transactional
    public void upsertAll(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        chunks.forEach(this::upsert);
    }

    @Override
    @Transactional
    public int deleteByKnowledgeId(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isBlank()) return 0;
        // Audience rows go with it via ON DELETE CASCADE.
        return jdbc.update("DELETE FROM ai_knowledge_chunk WHERE knowledge_id = ?", knowledgeId);
    }

    @Override
    @Transactional
    public int deleteBySourceRefPrefix(String sourceRefPrefix) {
        if (sourceRefPrefix == null || sourceRefPrefix.isBlank()) return 0;
        // Bound as a parameter, and LIKE metacharacters in the prefix are escaped so a source_ref
        // containing '%' cannot widen the delete beyond what the caller asked for.
        String escaped = sourceRefPrefix.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return jdbc.update(
                "DELETE FROM ai_knowledge_chunk WHERE source_ref LIKE ? ESCAPE '!'", escaped + "%");
    }

    @Override
    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM ai_knowledge_chunk", Long.class);
        return n == null ? 0L : n;
    }

    @Override
    public Optional<Instant> lastIndexedAt() {
        Timestamp ts = jdbc.queryForObject("SELECT max(indexed_at) FROM ai_knowledge_chunk", Timestamp.class);
        return Optional.ofNullable(ts).map(Timestamp::toInstant);
    }

    /**
     * Audience-filtered approximate nearest-neighbour search.
     *
     * <p>The audience predicate is part of this statement, never a filter applied to the results in
     * Java. Post-filtering would let unauthorised chunks occupy top-k slots and quietly starve the
     * answer of the content the caller is actually allowed to see — the user would get a worse
     * answer, or an UNKNOWN, with nothing in the logs to explain why.
     *
     * <p>Shaped as an inner ORDER BY/LIMIT with the score threshold applied outside so the HNSW
     * index still drives the ordering; putting the threshold in the inner WHERE would force a scan.
     * No module/page boosting happens here — that is a ranking concern applied over these
     * candidates by the retrieval layer, which is why it asks for more rows than it finally keeps.
     */
    @Override
    public List<RetrievalResult> search(RetrievalQuery query) {
        if (query == null || query.getEmbedding() == null) return List.of();

        Set<AudienceBucket> audiences = query.getAudiences();
        if (audiences == null || audiences.isEmpty()) {
            // Fail closed. No audiences means we cannot establish that the caller may read
            // anything, so they read nothing — never "therefore everything".
            log.warn("Knowledge search attempted with no audience buckets; returning no results");
            return List.of();
        }

        int topK = query.getTopK() > 0 ? query.getTopK() : 8;
        String vector = toVectorLiteral(query.getEmbedding());

        StringJoiner placeholders = new StringJoiner(", ");
        List<Object> params = new ArrayList<>();
        params.add(vector);                                   // score expression
        audiences.forEach(a -> placeholders.add("?"));
        audiences.forEach(a -> params.add(a.name()));
        params.add(vector);                                   // ORDER BY
        params.add(topK);
        params.add(query.getMinScore());

        String sql = """
                SELECT * FROM (
                    SELECT c.knowledge_id, c.chunk_ordinal, c.knowledge_type, c.module, c.page_id,
                           c.source_ref, c.title, c.body,
                           1 - (c.embedding <=> ?::vector) AS score
                    FROM ai_knowledge_chunk c
                    WHERE EXISTS (
                        SELECT 1 FROM ai_knowledge_chunk_audience a
                        WHERE a.chunk_id = c.id AND a.audience IN (%s)
                    )
                    ORDER BY c.embedding <=> ?::vector
                    LIMIT ?
                ) ranked
                WHERE ranked.score >= ?
                ORDER BY ranked.score DESC
                """.formatted(placeholders.toString());

        return jdbc.query(sql, RESULT_MAPPER, params.toArray());
    }

    private static final RowMapper<RetrievalResult> RESULT_MAPPER = (rs, rowNum) ->
            RetrievalResult.builder()
                    .knowledgeId(rs.getString("knowledge_id"))
                    .chunkOrdinal(rs.getInt("chunk_ordinal"))
                    .type(KnowledgeType.fromCode(rs.getString("knowledge_type")).orElse(null))
                    .module(rs.getString("module"))
                    .pageId(rs.getString("page_id"))
                    .sourceRef(rs.getString("source_ref"))
                    .title(rs.getString("title"))
                    .body(rs.getString("body"))
                    .score(rs.getDouble("score"))
                    .build();

    private void requireEmbedding(KnowledgeChunk chunk) {
        if (chunk == null || chunk.getEmbedding() == null || chunk.getEmbedding().length == 0) {
            throw new IllegalArgumentException(
                    "Cannot index a chunk without an embedding: "
                            + (chunk == null ? "null chunk" : chunk.getKnowledgeId()));
        }
    }

    private Set<AudienceBucket> safeAudiences(KnowledgeChunk chunk) {
        Set<AudienceBucket> audiences = chunk.getAudiences();
        if (audiences == null || audiences.isEmpty()) {
            // The schema validator should have caught this while loading the source. Refusing here
            // too means an un-tagged chunk can never reach the index, where (unlike help content)
            // no audience rows means readable by nobody and the unit would simply vanish silently.
            throw new IllegalArgumentException(
                    "Knowledge unit " + chunk.getKnowledgeId() + " has no audience; refusing to index it");
        }
        return audiences;
    }

    private String writeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unserializable knowledge metadata", e);
        }
    }

    /** pgvector's text input form: {@code [0.1,0.2,...]}, cast with {@code ?::vector}. */
    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder(embedding.length * 8 + 2).append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }
}
