package com.nforce.onehr.service.search;

import com.nforce.onehr.dto.search.SearchGroupDto;
import com.nforce.onehr.dto.search.SearchPageResponse;
import com.nforce.onehr.dto.search.SearchPreviewResponse;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Orchestrates every registered {@link SearchProvider} (Spring injects one per module — see that
 * interface's javadoc) behind the two endpoints {@link com.nforce.onehr.controller.GlobalSearchController}
 * exposes. Holds no module-specific knowledge itself: query validation, and fanning the same
 * validated query out to each provider, is everything this class does.
 */
@Service
@RequiredArgsConstructor
public class GlobalSearchService {

    static final int MIN_QUERY_LENGTH = 2;
    static final int MAX_QUERY_LENGTH = 100;
    private static final int PREVIEW_LIMIT_PER_MODULE = 5;
    private static final int MAX_PAGE_SIZE = 50;

    private final List<SearchProvider> providers;
    private final UserRepository userRepo;

    @Transactional(readOnly = true)
    public SearchPreviewResponse preview(String actorEmail, String rawQuery) {
        String query = validate(rawQuery);
        User actor = requireUser(actorEmail);

        List<SearchGroupDto> groups = providers.stream()
                .map(p -> toGroup(p, actor, query))
                .filter(Objects::nonNull)
                .toList();

        return SearchPreviewResponse.builder().query(query).groups(groups).build();
    }

    @Transactional(readOnly = true)
    public SearchPageResponse modulePage(String actorEmail, String moduleKey, String rawQuery, int page, int size) {
        String query = validate(rawQuery);
        User actor = requireUser(actorEmail);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        SearchProvider provider = providers.stream()
                .filter(p -> p.moduleKey().equalsIgnoreCase(moduleKey))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Unknown search module: " + moduleKey));

        SearchProvider.SearchProviderResult result = provider.page(actor, query, safePage, safeSize);
        int totalPages = (int) Math.ceil(result.totalCount() / (double) safeSize);

        return SearchPageResponse.builder()
                .module(provider.moduleKey())
                .label(provider.moduleLabel())
                .items(result.items())
                .page(safePage)
                .size(safeSize)
                .totalElements(result.totalCount())
                .totalPages(totalPages)
                .refineUrl(provider.refineUrl(actor, query))
                .build();
    }

    private SearchGroupDto toGroup(SearchProvider provider, User actor, String query) {
        SearchProvider.SearchProviderResult result = provider.preview(actor, query, PREVIEW_LIMIT_PER_MODULE);
        if (result.items().isEmpty()) return null;
        return SearchGroupDto.builder()
                .module(provider.moduleKey())
                .label(provider.moduleLabel())
                .items(result.items())
                .totalCount(result.totalCount())
                .refineUrl(provider.refineUrl(actor, query))
                .build();
    }

    private String validate(String rawQuery) {
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            throw new IllegalArgumentException("Search query is required");
        }
        String query = rawQuery.trim();
        if (query.length() < MIN_QUERY_LENGTH) {
            throw new IllegalArgumentException("Search query must be at least " + MIN_QUERY_LENGTH + " characters");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Search query must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        return query;
    }

    private User requireUser(String email) {
        return userRepo.findByEmail(email)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + email));
    }
}
