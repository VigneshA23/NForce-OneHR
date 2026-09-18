package com.nforce.onehr.ai.knowledge;

import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.KnowledgeDocument;
import com.nforce.onehr.ai.contract.KnowledgeType;
import com.nforce.onehr.entity.HelpContent;
import com.nforce.onehr.entity.HelpContentAudience;
import com.nforce.onehr.repository.HelpContentAudienceRepository;
import com.nforce.onehr.repository.HelpContentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ingestion of published Help &amp; Guidance content.
 *
 * <p>The behaviour worth pinning here is what happens at the edges HR can actually create: an
 * article with no audience tags, an article that is two words long, and an article whose text is
 * split across description and body depending on whether it is an FAQ or a guide.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HelpContentKnowledgeSourceTest {

    @Mock private HelpContentRepository helpContentRepository;
    @Mock private HelpContentAudienceRepository audienceRepository;

    @InjectMocks private HelpContentKnowledgeSource source;

    private HelpContent article(UUID id, String description, String body) {
        return HelpContent.builder()
                .id(id)
                .type("FAQ")
                .title("How do I apply for leave?")
                .description(description)
                .body(body)
                .category("Leave")
                .status("PUBLISHED")
                .build();
    }

    private void given(List<HelpContent> content, List<HelpContentAudience> tags) {
        when(helpContentRepository.findAll(any(Specification.class))).thenReturn(content);
        when(audienceRepository.findAll()).thenReturn(tags);
    }

    @Test
    @DisplayName("content is read through a specification, never as an unfiltered findAll")
    void alwaysReadsThroughASpecification() {
        given(List.of(), List.of());

        source.load();

        // The published/archived gate lives entirely in that specification. Someone "simplifying"
        // this to findAll() would start indexing drafts and unpublished articles with no other
        // visible symptom, so the shape of the call is itself worth asserting.
        verify(helpContentRepository).findAll(any(Specification.class));
        verify(helpContentRepository, never()).findAll();
    }

    @Test
    @DisplayName("an article with no audience tags is visible to every bucket")
    void untaggedContentIsVisibleToEveryone() {
        UUID id = UUID.randomUUID();
        given(List.of(article(id, "Open Leave and Holidays and choose Apply for Leave.", null)), List.of());

        KnowledgeDocument document = source.load().get(0);

        // Matches what /help already does today: no rows in help_content_audience means everyone
        // sees it. Being stricter here would hide articles the user can plainly read on the page.
        assertThat(document.getAudiences())
                .containsExactlyInAnyOrder(AudienceBucket.values());
    }

    @Test
    @DisplayName("audience tags are carried across, and an unrecognised tag is dropped")
    void taggedContentKeepsOnlyRecognisedBuckets() {
        UUID id = UUID.randomUUID();
        given(List.of(article(id, "Approving leave for your direct reports, step by step.", null)),
                List.of(
                        HelpContentAudience.builder().id(UUID.randomUUID()).contentId(id).audience("MANAGER").build(),
                        HelpContentAudience.builder().id(UUID.randomUUID()).contentId(id).audience("HR").build(),
                        HelpContentAudience.builder().id(UUID.randomUUID()).contentId(id).audience("LEADERSHIP").build()));

        KnowledgeDocument document = source.load().get(0);

        // LEADERSHIP is a real OneHR role but not an audience bucket - it folds into EMPLOYEE for
        // visibility purposes and must not silently become one here.
        assertThat(document.getAudiences())
                .containsExactlyInAnyOrder(AudienceBucket.MANAGER, AudienceBucket.HR);
    }

    @Test
    @DisplayName("tags belonging to a different article are not applied")
    void tagsAreMatchedToTheirOwnArticle() {
        UUID mine = UUID.randomUUID();
        given(List.of(article(mine, "Applying for leave is done from Leave and Holidays.", null)),
                List.of(HelpContentAudience.builder()
                        .id(UUID.randomUUID()).contentId(UUID.randomUUID()).audience("ADMIN").build()));

        KnowledgeDocument document = source.load().get(0);

        assertThat(document.getAudiences()).containsExactlyInAnyOrder(AudienceBucket.values());
    }

    @Test
    @DisplayName("an article too short to be useful is skipped rather than failing the re-index")
    void tooShortArticlesAreSkippedNotThrown() {
        given(List.of(
                article(UUID.randomUUID(), "See HR.", null),
                article(UUID.randomUUID(), "Open Leave and Holidays, then choose Apply for Leave.", null)),
                List.of());

        List<KnowledgeDocument> documents = source.load();

        // The whole point: one badly written FAQ must not take down every other knowledge source
        // with it. YAML fails loudly because it goes through review; this is live data typed by a
        // person into a form.
        assertThat(documents).hasSize(1);
    }

    @Test
    @DisplayName("description and body are both embedded, whichever one the author used")
    void descriptionAndBodyAreBothIndexed() {
        given(List.of(article(UUID.randomUUID(),
                "Leave is requested from the Leave and Holidays page.",
                "Managers approve within two working days.")), List.of());

        KnowledgeDocument document = source.load().get(0);

        // An FAQ usually carries its answer in description with an empty body; a guide is the
        // reverse. Embedding one field would quietly lose half the help centre.
        assertThat(document.getBody())
                .contains("Leave is requested from the Leave and Holidays page.")
                .contains("Managers approve within two working days.");
    }

    @Test
    @DisplayName("ids and sourceRef line up with the prefix a re-index deletes by")
    void identifiersMatchTheDeletionPrefix() {
        UUID id = UUID.randomUUID();
        given(List.of(article(id, "Open Leave and Holidays and choose Apply for Leave.", null)), List.of());

        KnowledgeDocument document = source.load().get(0);

        // If sourceRef ever stopped starting with sourceRefPrefix(), deleteBySourceRefPrefix would
        // stop matching and unpublished articles would stay retrievable forever - the one failure
        // mode of this source that nobody would notice until it mattered.
        assertThat(document.getSourceRef()).startsWith(source.sourceRefPrefix()).endsWith(id.toString());
        assertThat(document.getKnowledgeId()).isEqualTo("help." + id);
        assertThat(document.getType()).isEqualTo(KnowledgeType.FAQ);
        assertThat(document.getPageId()).isEqualTo("help");
    }

    @Test
    @DisplayName("metadata keeps the row id and the original FAQ/GUIDE distinction")
    void metadataPreservesWhatTheTypeCollapseLoses() {
        UUID id = UUID.randomUUID();
        HelpContent guide = article(id, "A walkthrough of the regularization request flow.", null);
        guide.setType("GUIDE");
        given(List.of(guide), List.of());

        KnowledgeDocument document = source.load().get(0);

        assertThat(document.getMetadata())
                .containsEntry("helpContentId", id.toString())
                .containsEntry("helpContentType", "GUIDE")
                .containsEntry("category", "Leave");
    }
}
