package com.nforce.onehr.ai.retrieval;

import com.nforce.onehr.ai.config.AiProperties;
import com.nforce.onehr.ai.contract.EmbeddingProvider;
import com.nforce.onehr.ai.contract.KnowledgeIndexRepository;
import com.nforce.onehr.ai.contract.KnowledgeRetriever;
import com.nforce.onehr.ai.contract.RetrievalQuery;
import com.nforce.onehr.ai.contract.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authorised semantic retrieval: embed the question, fetch candidates the caller may read, re-rank
 * them against the page they are on, and trim to what will fit in a prompt.
 *
 * <p>The audience filter is not applied here — it lives in the SQL
 * ({@code PgVectorKnowledgeIndexRepository#search}) so unauthorised chunks never occupy a top-k
 * slot in the first place. Everything this class does is ranking and budgeting on results that are
 * already authorised.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VectorKnowledgeRetriever implements KnowledgeRetriever {

    /**
     * How much a chunk gains for belonging to the module or page the user is looking at.
     *
     * <p>Small on purpose. "Where do I regularize attendance?" asked from the Leave page must still
     * return the attendance answer — the page is a hint about what the user probably means, not a
     * statement about what they asked. A large boost would turn a weak same-page match into a
     * confident wrong answer, which is worse than no boost at all.
     */
    private static final double PAGE_BOOST = 0.05;
    private static final double MODULE_BOOST = 0.03;

    private final KnowledgeIndexRepository index;
    private final EmbeddingProvider embeddingProvider;
    private final AiProperties properties;

    @Override
    public List<RetrievalResult> retrieve(RetrievalQuery query) {
        if (query == null || query.getRawQuery() == null || query.getRawQuery().isBlank()) {
            return List.of();
        }
        AiProperties.Retrieval cfg = properties.getRetrieval();

        RetrievalQuery resolved = withDefaults(query, cfg);
        if (resolved.getEmbedding() == null) {
            // Any provider failure propagates as AiProviderException so the service can degrade to
            // a controlled UNKNOWN. Swallowing it here would look like "no knowledge exists",
            // which is a different and misleading thing to tell the user.
            resolved.setEmbedding(embeddingProvider.embed(resolved.getRawQuery()));
        }

        // Over-fetch, because re-ranking after truncation can only reorder what survived. A chunk
        // that deserved a page boost but sat just outside top-k would be gone before the boost
        // could apply.
        int candidates = Math.max(resolved.getTopK(), resolved.getTopK() * Math.max(1, cfg.getCandidateMultiplier()));
        RetrievalQuery candidateQuery = RetrievalQuery.builder()
                .rawQuery(resolved.getRawQuery())
                .embedding(resolved.getEmbedding())
                .audiences(resolved.getAudiences())
                .moduleHint(resolved.getModuleHint())
                .pageIdHint(resolved.getPageIdHint())
                .topK(candidates)
                .minScore(resolved.getMinScore())
                .build();

        List<RetrievalResult> found = index.search(candidateQuery);
        if (found.isEmpty()) {
            log.debug("No authorised knowledge matched the query above the {} threshold", resolved.getMinScore());
            return List.of();
        }

        List<RetrievalResult> ranked = rerank(found, resolved);
        List<RetrievalResult> deduped = keepBestChunkPerUnit(ranked);
        return applyContextBudget(deduped, resolved.getTopK(), cfg.getMaxContextChars());
    }

    private RetrievalQuery withDefaults(RetrievalQuery query, AiProperties.Retrieval cfg) {
        return RetrievalQuery.builder()
                .rawQuery(query.getRawQuery())
                .embedding(query.getEmbedding())
                .audiences(query.getAudiences())
                .moduleHint(query.getModuleHint())
                .pageIdHint(query.getPageIdHint())
                .topK(query.getTopK() > 0 ? query.getTopK() : cfg.getTopK())
                .minScore(query.getMinScore() > 0 ? query.getMinScore() : cfg.getMinScore())
                .build();
    }

    private List<RetrievalResult> rerank(List<RetrievalResult> results, RetrievalQuery query) {
        if (query.getPageIdHint() == null && query.getModuleHint() == null) {
            return results;
        }
        List<RetrievalResult> boosted = new ArrayList<>(results.size());
        for (RetrievalResult r : results) {
            double score = r.getScore();
            if (query.getPageIdHint() != null && query.getPageIdHint().equals(r.getPageId())) {
                score += PAGE_BOOST;
            }
            if (query.getModuleHint() != null && query.getModuleHint().equals(r.getModule())) {
                score += MODULE_BOOST;
            }
            boosted.add(score == r.getScore() ? r : copyWithScore(r, Math.min(score, 1.0)));
        }
        boosted.sort(Comparator.comparingDouble(RetrievalResult::getScore).reversed());
        return boosted;
    }

    /**
     * One chunk per knowledge unit, keeping the best-scoring.
     *
     * <p>A long action split across several chunks would otherwise return three near-identical
     * neighbours and crowd out the workflow or permission knowledge a complete answer needs — the
     * prompt would look full while actually being narrower.
     */
    private List<RetrievalResult> keepBestChunkPerUnit(List<RetrievalResult> results) {
        Map<String, RetrievalResult> best = new LinkedHashMap<>();
        for (RetrievalResult r : results) {
            best.merge(r.getKnowledgeId(), r,
                    (existing, candidate) -> candidate.getScore() > existing.getScore() ? candidate : existing);
        }
        return new ArrayList<>(best.values());
    }

    /**
     * Trims to topK and to a character budget, whichever binds first.
     *
     * <p>Stops at the first chunk that would overflow rather than skipping it and trying smaller
     * ones: results are score-ordered, so packing the budget with weaker-but-shorter chunks would
     * mean dropping the most relevant knowledge to fit more of the less relevant.
     */
    private List<RetrievalResult> applyContextBudget(List<RetrievalResult> results, int topK, int maxChars) {
        List<RetrievalResult> kept = new ArrayList<>();
        int used = 0;
        for (RetrievalResult r : results) {
            if (kept.size() >= topK) break;
            int size = (r.getBody() == null ? 0 : r.getBody().length())
                    + (r.getTitle() == null ? 0 : r.getTitle().length());
            if (!kept.isEmpty() && used + size > maxChars) break;
            kept.add(r);
            used += size;
        }
        return List.copyOf(kept);
    }

    private RetrievalResult copyWithScore(RetrievalResult r, double score) {
        return RetrievalResult.builder()
                .knowledgeId(r.getKnowledgeId())
                .chunkOrdinal(r.getChunkOrdinal())
                .type(r.getType())
                .module(r.getModule())
                .pageId(r.getPageId())
                .sourceRef(r.getSourceRef())
                .title(r.getTitle())
                .body(r.getBody())
                .score(score)
                .build();
    }
}
