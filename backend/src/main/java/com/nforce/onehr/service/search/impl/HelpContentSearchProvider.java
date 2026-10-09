package com.nforce.onehr.service.search.impl;

import com.nforce.onehr.dto.helpcontent.HelpContentSummaryDto;
import com.nforce.onehr.dto.search.SearchResultItem;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.service.HelpContentService;
import com.nforce.onehr.service.search.SearchProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

import static com.nforce.onehr.service.search.SearchSupport.encode;

/**
 * Help Content / Knowledge Base — delegates to {@link HelpContentService#listPublished}, the same
 * published-and-audience-filtered search the Help & Guidance page's own content search box already
 * uses for every role (that widget never branches on admin vs employee — see HelpDeskPage.tsx).
 */
@Component
@RequiredArgsConstructor
public class HelpContentSearchProvider implements SearchProvider {

    private final HelpContentService helpContentService;

    @Override
    public String moduleKey() {
        return "HELP_CONTENT";
    }

    @Override
    public String moduleLabel() {
        return "Help & Guidance";
    }

    @Override
    public SearchProviderResult preview(User actor, String query, int limit) {
        return search(actor, query, 0, limit);
    }

    @Override
    public SearchProviderResult page(User actor, String query, int page, int size) {
        return search(actor, query, page, size);
    }

    @Override
    public String refineUrl(User actor, String query) {
        return "/help?contentSearch=" + encode(query);
    }

    private SearchProviderResult search(User actor, String query, int page, int size) {
        Page<HelpContentSummaryDto> result =
                helpContentService.listPublished(null, null, query, null, page, size, actor.getEmail());

        List<SearchResultItem> items = result.getContent().stream()
                .map(c -> SearchResultItem.builder()
                        .module(moduleKey())
                        .id(c.getId().toString())
                        .title(c.getTitle())
                        .subtitle(subtitleFor(c))
                        .detailUrl("/help?contentId=" + c.getId())
                        .build())
                .collect(Collectors.toList());
        return new SearchProviderResult(items, result.getTotalElements());
    }

    private String subtitleFor(HelpContentSummaryDto c) {
        return c.getCategory() != null ? c.getType() + " · " + c.getCategory() : c.getType();
    }
}
