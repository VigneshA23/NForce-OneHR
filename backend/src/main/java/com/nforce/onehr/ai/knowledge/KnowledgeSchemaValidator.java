package com.nforce.onehr.ai.knowledge;

import com.nforce.onehr.ai.contract.KnowledgeDocument;
import com.nforce.onehr.ai.navigation.PageRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks a loaded knowledge set before any of it is embedded or indexed.
 *
 * <p>Runs as one pass that collects every problem rather than throwing on the first, so an author
 * fixing a batch of files sees the whole list instead of rediscovering it one re-index at a time.
 *
 * <p>The check that matters most is {@code pageId} against the page registry. A unit pointing at a
 * page that does not exist produces the single most damaging failure this system has: the model
 * reads knowledge saying "go to X", proposes X, {@code NavigationValidator} correctly refuses it,
 * and the user gets an answer that describes a destination with no way to get there. Catching it
 * at index time turns that into a build failure naming the file.
 */
@Component
@RequiredArgsConstructor
public class KnowledgeSchemaValidator {

    /**
     * Shortest body worth an index slot.
     *
     * <p>Public because {@link HelpContentKnowledgeSource} has to apply the same threshold before
     * it emits a document — it skips a too-short article rather than letting this validator fail
     * the whole re-index over live HR data. Two independent constants would drift into a state
     * where the source emits a row this class then rejects, taking every other source down with it.
     */
    public static final int MIN_BODY_CHARS = 40;

    private final PageRegistry pageRegistry;

    /** @throws IllegalStateException listing every problem found */
    public void validate(List<KnowledgeDocument> documents) {
        List<String> problems = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (KnowledgeDocument doc : documents) {
            String where = doc.getKnowledgeId() + " (" + doc.getSourceRef() + ")";

            if (!seenIds.add(doc.getKnowledgeId())) {
                // Duplicate ids silently overwrite each other at upsert time, so one unit's content
                // would simply disappear with nothing to show for it.
                problems.add("duplicate knowledgeId: " + where);
            }
            if (doc.getAudiences() == null || doc.getAudiences().isEmpty()) {
                problems.add("no audience declared: " + where);
            }
            if (doc.getBody() == null || doc.getBody().trim().length() < MIN_BODY_CHARS) {
                // A near-empty body still embeds to a valid vector, so it can win a retrieval slot
                // and contribute nothing - crowding out a chunk that would have answered.
                problems.add("body is too short to be useful (min " + MIN_BODY_CHARS + " chars): " + where);
            }
            if (doc.getPageId() != null && !pageRegistry.exists(doc.getPageId())) {
                problems.add("unknown pageId '" + doc.getPageId() + "': " + where);
            }
            if (doc.getVersion() < 1) {
                problems.add("version must be at least 1: " + where);
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Knowledge validation failed with "
                    + problems.size() + " problem(s):\n  - " + String.join("\n  - ", problems));
        }
    }
}
