package com.nforce.onehr.ai.contract;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Storage seam for the knowledge index. pgvector is the first implementation.
 *
 * <p>This is the only write path in the AI feature that touches the database outside conversation
 * logging, and it can only ever reach {@code ai_knowledge_chunk*} tables. No OneHR domain table is
 * reachable from here, by construction.
 */
public interface KnowledgeIndexRepository {

    /**
     * Width of {@code ai_knowledge_chunk.embedding}, fixed by migration V185.
     *
     * <p>Lives on the contract rather than in the pgvector implementation because it is a fact
     * about the schema that indexing has to check <em>before</em> writing anything: an embedding
     * provider of a different width produces vectors that are individually valid and collectively
     * meaningless, and the failure would otherwise surface as quietly poor answers rather than an
     * error. Changing it means a migration and a full re-index.
     */
    int EMBEDDING_DIMENSIONS = 1024;

    /** Insert or replace a chunk by {@code (knowledgeId, chunkOrdinal)}. Requires a non-null embedding. */
    void upsert(KnowledgeChunk chunk);

    void upsertAll(List<KnowledgeChunk> chunks);

    /** Removes every chunk of a document. Returns rows deleted. */
    int deleteByKnowledgeId(String knowledgeId);

    /**
     * Removes every chunk whose {@code sourceRef} starts with the given prefix — how a re-index
     * retires units that disappeared from a source entirely (a deleted YAML file, an unpublished
     * help article), which a per-id upsert loop cannot detect.
     */
    int deleteBySourceRefPrefix(String sourceRefPrefix);

    long count();

    Optional<Instant> lastIndexedAt();

    /** Audience-filtered kNN search. The filter is part of the SQL, never a post-filter. */
    List<RetrievalResult> search(RetrievalQuery query);

    /**
     * Literal, audience-filtered lookup by id - no vector comparison at all. Every named unit this
     * caller is authorised for comes back, each given the fixed {@code score} passed in rather than
     * a computed one, since there is nothing to compute it from.
     *
     * <p>For a knowledge unit whose inclusion needs to be certain rather than probable: see {@code
     * VectorKnowledgeRetriever}'s deterministic inclusion of the named-person-lookup unit, which
     * cannot rely on cosine similarity clearing the noise floor for an arbitrary name it has never
     * seen (ONEHR - AI chatbot fails to handle duplicate employee names).
     */
    List<RetrievalResult> findByIds(Set<String> knowledgeIds, Set<AudienceBucket> audiences, double score);
}
