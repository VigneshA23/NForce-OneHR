package com.nforce.onehr.service.search.impl;

import com.nforce.onehr.dto.doc.AnnouncementResponse;
import com.nforce.onehr.dto.search.SearchResultItem;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.service.AnnouncementService;
import com.nforce.onehr.service.search.RelevanceScorer;
import com.nforce.onehr.service.search.SearchProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static com.nforce.onehr.service.search.SearchSupport.encode;
import static com.nforce.onehr.service.search.SearchSupport.paginate;

/**
 * Announcements — reuses {@link AnnouncementService#listPublished()}, the same published/active
 * list every authenticated user already sees on the Announcements tab of My Documents & Policies
 * (no audience-based narrowing exists there today, so none is added here).
 */
@Component
@RequiredArgsConstructor
public class AnnouncementSearchProvider implements SearchProvider {

    private final AnnouncementService announcementService;

    @Override
    public String moduleKey() {
        return "ANNOUNCEMENTS";
    }

    @Override
    public String moduleLabel() {
        return "Announcements";
    }

    @Override
    public SearchProviderResult preview(User actor, String query, int limit) {
        return search(query, 0, limit);
    }

    @Override
    public SearchProviderResult page(User actor, String query, int page, int size) {
        return search(query, page, size);
    }

    @Override
    public String refineUrl(User actor, String query) {
        return "/my-documents?tab=announcements&search=" + encode(query);
    }

    private SearchProviderResult search(String query, int page, int size) {
        List<Scored> ranked = announcementService.listPublished().stream()
                .map(a -> new Scored(a, RelevanceScorer.bestScore(query, a.getTitle(), a.getBody())))
                .filter(s -> s.score > 0)
                .sorted(Comparator.<Scored>comparingInt(s -> s.score).reversed()
                        .thenComparing(s -> s.ann.getPublishedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());

        List<SearchResultItem> items = paginate(ranked, page, size).stream()
                .map(s -> {
                    AnnouncementResponse a = s.ann;
                    return SearchResultItem.builder()
                            .module(moduleKey())
                            .id(a.getId().toString())
                            .title(a.getTitle())
                            .subtitle(snippet(a.getBody()))
                            .detailUrl("/my-documents?tab=announcements&search=" + encode(a.getTitle()))
                            .build();
                })
                .collect(Collectors.toList());
        return new SearchProviderResult(items, ranked.size());
    }

    private String snippet(String body) {
        if (body == null) return "";
        String trimmed = body.trim();
        return trimmed.length() > 120 ? trimmed.substring(0, 117) + "..." : trimmed;
    }

    private record Scored(AnnouncementResponse ann, int score) {
    }
}
