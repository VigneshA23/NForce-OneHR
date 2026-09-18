package com.nforce.onehr.ai.knowledge;

import com.nforce.onehr.ai.contract.EmbeddingProvider;
import com.nforce.onehr.ai.contract.KnowledgeChunk;
import com.nforce.onehr.ai.contract.KnowledgeDocument;
import com.nforce.onehr.ai.contract.KnowledgeIndexRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds the knowledge index from every {@link KnowledgeSource}.
 *
 * <p>Triggered explicitly by an admin endpoint rather than on startup or on a schedule. Startup
 * indexing would make every deploy pay for a full embedding run and would couple OneHR booting to a
 * third-party API being reachable — a bad trade for a system whose core job is HR administration.
 *
 * <p>Deletion is by {@code sourceRef} prefix per source, because an upsert loop can only ever add
 * or update. A unit deleted from a YAML file, or an article that was unpublished, leaves no trace
 * to upsert against and would otherwise stay retrievable forever — the unpublished case being the
 * one that actually matters, since it would keep serving content someone deliberately withdrew.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeIndexingService {

    private final List<KnowledgeSource> sources;
    private final KnowledgeSchemaValidator validator;
    private final KnowledgeChunker chunker;
    private final EmbeddingProvider embeddingProvider;
    private final KnowledgeIndexRepository index;

    /**
     * Full rebuild: load, validate, chunk, embed, replace.
     *
     * <p>Validation runs across every source together before anything is written, so a broken file
     * cannot leave the index half-rebuilt. The embedding width is checked first, because
     * discovering a model mismatch after writing several hundred rows would mean an index full of
     * vectors that are individually valid and collectively meaningless.
     */
    public IndexingReport reindex() {
        Instant started = Instant.now();
        assertEmbeddingWidthMatchesSchema();

        List<KnowledgeDocument> documents = new ArrayList<>();
        for (KnowledgeSource source : sources) {
            List<KnowledgeDocument> loaded = source.load();
            log.info("Knowledge source '{}' provided {} documents", source.name(), loaded.size());
            documents.addAll(loaded);
        }

        validator.validate(documents);

        List<KnowledgeChunk> chunks = chunker.chunkAll(documents);
        if (chunks.isEmpty()) {
            log.warn("Re-index found no knowledge; leaving the existing index untouched");
            return IndexingReport.builder()
                    .documents(0).chunks(0).removed(0)
                    .startedAt(started).finishedAt(Instant.now())
                    .build();
        }

        embed(chunks);

        int removed = 0;
        for (KnowledgeSource source : sources) {
            removed += index.deleteBySourceRefPrefix(source.sourceRefPrefix());
        }
        index.upsertAll(chunks);

        IndexingReport report = IndexingReport.builder()
                .documents(documents.size())
                .chunks(chunks.size())
                .removed(removed)
                .startedAt(started)
                .finishedAt(Instant.now())
                .build();
        log.info("Re-index complete: {} documents, {} chunks, {} stale rows removed, {} ms",
                report.getDocuments(), report.getChunks(), report.getRemoved(), report.durationMillis());
        return report;
    }

    /** Batched so one oversized request cannot fail an entire re-index. */
    private void embed(List<KnowledgeChunk> chunks) {
        List<String> texts = chunks.stream().map(KnowledgeChunk::getBody).toList();
        List<float[]> vectors = embeddingProvider.embedBatch(texts);
        if (vectors.size() != chunks.size()) {
            throw new IllegalStateException(
                    "Embedding provider returned " + vectors.size() + " vectors for " + chunks.size() + " chunks");
        }
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setEmbedding(vectors.get(i));
        }
    }

    private void assertEmbeddingWidthMatchesSchema() {
        int dims = embeddingProvider.dimensions();
        if (dims != KnowledgeIndexRepository.EMBEDDING_DIMENSIONS) {
            throw new IllegalStateException("Embedding provider '" + embeddingProvider.name()
                    + "' produces " + dims + "-dimension vectors, but ai_knowledge_chunk.embedding is "
                    + KnowledgeIndexRepository.EMBEDDING_DIMENSIONS
                    + ". Change the embed model back, or migrate the column width and re-index.");
        }
    }

    /** Current index state, for the admin health endpoint. */
    public IndexHealth health() {
        return IndexHealth.builder()
                .chunks(index.count())
                .lastIndexedAt(index.lastIndexedAt().orElse(null))
                .sources(sources.stream().map(KnowledgeSource::name).toList())
                .embeddingProvider(embeddingProvider.name())
                .embeddingDimensions(embeddingProvider.dimensions())
                .build();
    }

    @Data
    @Builder
    public static class IndexingReport {
        private int documents;
        private int chunks;
        private int removed;
        private Instant startedAt;
        private Instant finishedAt;

        public long durationMillis() {
            return finishedAt == null || startedAt == null ? 0 : finishedAt.toEpochMilli() - startedAt.toEpochMilli();
        }
    }

    @Data
    @Builder
    public static class IndexHealth {
        private long chunks;
        private Instant lastIndexedAt;
        private List<String> sources;
        private String embeddingProvider;
        private int embeddingDimensions;

        public boolean isReady() {
            return chunks > 0;
        }
    }
}
