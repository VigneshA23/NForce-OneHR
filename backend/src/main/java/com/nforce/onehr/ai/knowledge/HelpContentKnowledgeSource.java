package com.nforce.onehr.ai.knowledge;

import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.KnowledgeDocument;
import com.nforce.onehr.ai.contract.KnowledgeType;
import com.nforce.onehr.entity.HelpContent;
import com.nforce.onehr.entity.HelpContentAudience;
import com.nforce.onehr.repository.HelpContentAudienceRepository;
import com.nforce.onehr.repository.HelpContentRepository;
import com.nforce.onehr.repository.HelpContentSpecifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Ingests published Help &amp; Guidance articles as retrievable knowledge.
 *
 * <p>This is the one knowledge source HR owns directly. An HR Admin publishes an FAQ and the
 * assistant can answer from it after the next re-index, with no code change and no YAML edit —
 * which is the whole point of reusing the existing feature rather than building a second authoring
 * surface beside it.
 *
 * <p><strong>The visibility gate is the existing specification, not a new query.</strong> Reading
 * through {@link HelpContentSpecifications#publishedAndActive()} means draft, pending, approved,
 * unpublished and archived rows are <em>structurally unable</em> to reach the index: there is no
 * code path here that could return them, so "the assistant quoted an unpublished article" cannot
 * happen through an oversight in this class. {@code audienceVisibleTo} is applied over all four
 * buckets as well — a no-op for well-formed rows, but it drops a row whose only audience tag is an
 * unrecognised value rather than indexing it as visible to nobody.
 *
 * <p><strong>Nothing about Help &amp; Guidance changes.</strong> There is no listener on
 * {@code HelpContentService}, no new column and no write path — re-indexing is explicit, so
 * publishing an article behaves exactly as it did before this feature existed. The cost is that a
 * newly published article is not retrievable until someone re-indexes; that is the right trade for
 * v1, and an event listener can be added later once the flow has proven itself.
 *
 * <p><strong>Unlike the YAML source, this one never throws on bad content.</strong> YAML is ours
 * and arrives through review, so a malformed unit should fail loudly. Help content is live data
 * typed by a person into a form: a one-line FAQ must not be able to take down the entire re-index
 * and with it every piece of authored knowledge. Unusable rows are skipped, counted and logged.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HelpContentKnowledgeSource implements KnowledgeSource {

    /** Every bucket the index understands — used to express "no tags means everyone". */
    private static final Set<AudienceBucket> ALL_AUDIENCES =
            EnumSet.allOf(AudienceBucket.class);

    /**
     * Help articles are about using OneHR, so they belong to the Help page for the purposes of the
     * current-page retrieval boost: someone asking a question while sitting on {@code /help} is
     * more likely than average to want one.
     */
    private static final String HELP_PAGE_ID = "help";
    private static final String HELP_MODULE = "help";

    private final HelpContentRepository helpContentRepository;
    private final HelpContentAudienceRepository audienceRepository;

    @Override
    public String name() {
        return "help-content";
    }

    @Override
    public String sourceRefPrefix() {
        return "help_content:";
    }

    /**
     * Read-only by declaration as well as by behaviour — this loads during an admin re-index and
     * must not be capable of touching a help content row.
     */
    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeDocument> load() {
        List<HelpContent> published = helpContentRepository.findAll(Specification.allOf(
                HelpContentSpecifications.publishedAndActive(),
                HelpContentSpecifications.audienceVisibleTo(
                        ALL_AUDIENCES.stream().map(Enum::name).collect(Collectors.toSet()))));

        Map<UUID, Set<AudienceBucket>> audiences = audiencesFor(published);

        List<KnowledgeDocument> documents = new ArrayList<>(published.size());
        int skipped = 0;
        for (HelpContent content : published) {
            KnowledgeDocument document = toDocument(content, audiences.get(content.getId()));
            if (document == null) {
                skipped++;
                continue;
            }
            documents.add(document);
        }

        if (skipped > 0) {
            log.warn("Skipped {} published help article(s) with too little text to index usefully", skipped);
        }
        log.info("Loaded {} knowledge documents from published Help & Guidance content", documents.size());
        return documents;
    }

    /**
     * Tags for every row in one query.
     *
     * <p>Per-row lookups would be a query per article on a path that already runs rarely but reads
     * everything; one pass keeps a re-index of a few hundred articles to two queries.
     */
    private Map<UUID, Set<AudienceBucket>> audiencesFor(List<HelpContent> content) {
        Set<UUID> ids = content.stream().map(HelpContent::getId).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();

        Map<UUID, Set<AudienceBucket>> byContent = new LinkedHashMap<>();
        for (HelpContentAudience tag : audienceRepository.findAll()) {
            if (!ids.contains(tag.getContentId())) continue;
            AudienceBucket.fromCode(tag.getAudience()).ifPresent(bucket ->
                    byContent.computeIfAbsent(tag.getContentId(), key -> new LinkedHashSet<>()).add(bucket));
        }
        return byContent;
    }

    /**
     * @return the document, or null when the row carries too little text to be worth a retrieval slot
     */
    private KnowledgeDocument toDocument(HelpContent content, Set<AudienceBucket> tagged) {
        String body = composeBody(content);
        if (body.length() < KnowledgeSchemaValidator.MIN_BODY_CHARS) {
            // Deliberately not an exception. A short row is a content problem for HR to fix, not a
            // reason to leave OneHR without an assistant until they do.
            log.debug("Help article {} has only {} characters of text; not indexed",
                    content.getId(), body.length());
            return null;
        }

        return KnowledgeDocument.builder()
                .knowledgeId("help." + content.getId())
                .type(KnowledgeType.FAQ)
                .module(HELP_MODULE)
                .pageId(HELP_PAGE_ID)
                // Help content has no author-managed version, and its row id is stable across
                // edits only while it stays published — an edit forks a draft and the published
                // row keeps its id, so the id is the right anchor and the version is always 1.
                .version(1)
                .audiences(resolveAudiences(tagged))
                .sourceRef(sourceRefPrefix() + content.getId())
                .title(content.getTitle())
                .body(body)
                .synonyms(List.of())
                .metadata(metadataFor(content))
                .build();
    }

    /**
     * Applies Help &amp; Guidance's own "no tags means everyone" rule.
     *
     * <p>This is the opposite of the authored-YAML rule, where a missing audience is a build
     * failure. Both are right: an untagged YAML unit means the author forgot, while an untagged
     * help article means it predates audience targeting or was never narrowed — and today OneHR
     * genuinely shows it to everyone. Making the assistant stricter than the page the content
     * already appears on would hide articles users can plainly see.
     */
    private Set<AudienceBucket> resolveAudiences(Set<AudienceBucket> tagged) {
        return tagged == null || tagged.isEmpty() ? ALL_AUDIENCES : tagged;
    }

    /**
     * Folds title, description and body into the single text that gets embedded.
     *
     * <p>An FAQ typically carries its whole answer in {@code description} with an empty
     * {@code body}, while a guide is the reverse. Embedding only one field would silently lose
     * half the help centre depending on which type it was.
     */
    private String composeBody(HelpContent content) {
        StringBuilder sb = new StringBuilder();
        append(sb, content.getDescription());
        append(sb, content.getBody());
        return sb.toString().trim();
    }

    private void append(StringBuilder sb, String text) {
        if (text == null || text.isBlank()) return;
        if (sb.length() > 0) sb.append("\n\n");
        sb.append(text.trim());
    }

    /**
     * Carried into {@code ai_knowledge_chunk.metadata} so a retrieved article can be traced back to
     * the row an HR Admin would edit, and so the original FAQ/GUIDE distinction survives being
     * collapsed into {@link KnowledgeType#FAQ}.
     */
    private Map<String, Object> metadataFor(HelpContent content) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("helpContentId", String.valueOf(content.getId()));
        metadata.put("helpContentType", content.getType());
        if (content.getCategory() != null && !content.getCategory().isBlank()) {
            metadata.put("category", content.getCategory());
        }
        return metadata;
    }
}
