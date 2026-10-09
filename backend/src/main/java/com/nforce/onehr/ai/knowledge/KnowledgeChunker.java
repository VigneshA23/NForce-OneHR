package com.nforce.onehr.ai.knowledge;

import com.nforce.onehr.ai.contract.KnowledgeChunk;
import com.nforce.onehr.ai.contract.KnowledgeDocument;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Turns authored documents into indexable chunks.
 *
 * <p><strong>One chunk per authored unit, not a fixed token window.</strong> The units are already
 * the right size, because they were written as one coherent thing: an action with its steps,
 * validations and outcome; a workflow with its stages. Splitting on a character count would cut an
 * action's preconditions away from its steps, and retrieving half of that is worse than not
 * retrieving it — the model would answer confidently from an instruction set with the caveats
 * removed.
 *
 * <p>The only exception is a unit that would not fit a prompt at all, which is split on paragraph
 * boundaries as a backstop. That should be rare enough to be a signal the unit wants splitting by
 * hand into two properly-titled units.
 */
@Component
public class KnowledgeChunker {

    /**
     * Above this, a single unit starts crowding out everything else retrieved alongside it. Well
     * under the 12000-char prompt budget so several chunks can coexist.
     */
    private static final int MAX_CHUNK_CHARS = 4000;

    public List<KnowledgeChunk> chunk(KnowledgeDocument doc) {
        List<String> bodies = splitIfOversized(doc.getBody());
        List<KnowledgeChunk> chunks = new ArrayList<>(bodies.size());

        for (int ordinal = 0; ordinal < bodies.size(); ordinal++) {
            String embeddedText = buildEmbeddedText(doc, bodies.get(ordinal));
            chunks.add(KnowledgeChunk.builder()
                    .knowledgeId(doc.getKnowledgeId())
                    .chunkOrdinal(ordinal)
                    .type(doc.getType())
                    .module(doc.getModule())
                    .pageId(doc.getPageId())
                    .sourceRef(doc.getSourceRef())
                    .version(doc.getVersion())
                    .contentHash(sha256(embeddedText))
                    .title(doc.getTitle())
                    .body(embeddedText)
                    .metadata(doc.getMetadata())
                    .audiences(doc.getAudiences())
                    .build());
        }
        return chunks;
    }

    public List<KnowledgeChunk> chunkAll(List<KnowledgeDocument> documents) {
        List<KnowledgeChunk> all = new ArrayList<>();
        documents.forEach(doc -> all.addAll(chunk(doc)));
        return all;
    }

    /**
     * The text that is both embedded and shown to the model.
     *
     * <p>Title and synonyms are folded in rather than kept as separate metadata because retrieval
     * is semantic: "fix my attendance" only finds the regularization unit if that phrasing is part
     * of what was embedded. Keeping synonyms out of the vector would make them decorative.
     */
    private String buildEmbeddedText(KnowledgeDocument doc, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append(doc.getTitle()).append('\n');
        if (doc.getSynonyms() != null && !doc.getSynonyms().isEmpty()) {
            sb.append("Also asked as: ").append(String.join("; ", doc.getSynonyms())).append('\n');
        }
        sb.append('\n').append(body.trim());
        return sb.toString();
    }

    /** Splits on blank lines only, so a paragraph is never cut mid-thought. */
    private List<String> splitIfOversized(String body) {
        String trimmed = body.trim();
        if (trimmed.length() <= MAX_CHUNK_CHARS) return List.of(trimmed);

        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : trimmed.split("\\n\\s*\\n")) {
            if (current.length() > 0 && current.length() + paragraph.length() > MAX_CHUNK_CHARS) {
                parts.add(current.toString().trim());
                current.setLength(0);
            }
            current.append(paragraph).append("\n\n");
        }
        if (current.length() > 0) parts.add(current.toString().trim());
        return parts;
    }

    /** Lets a re-index skip unchanged chunks rather than paying for an embedding call per unit. */
    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
