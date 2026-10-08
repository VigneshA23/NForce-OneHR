package com.nforce.onehr.service.search;

import com.nforce.onehr.dto.search.SearchGroupDto;
import com.nforce.onehr.dto.search.SearchPageResponse;
import com.nforce.onehr.dto.search.SearchPreviewResponse;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates every registered {@link SearchProvider} (Spring injects one per module — see that
 * interface's javadoc) behind the two endpoints {@link com.nforce.onehr.controller.GlobalSearchController}
 * exposes. Holds no module-specific knowledge itself: query validation, and fanning the same
 * validated query out to each provider, is everything this class does.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GlobalSearchService {

    static final int MIN_QUERY_LENGTH = 2;
    static final int MAX_QUERY_LENGTH = 100;
    private static final int PREVIEW_LIMIT_PER_MODULE = 5;
    private static final int MAX_PAGE_SIZE = 50;
    // Bounds how long one module can hold up the others. preview() fans out across every
    // SearchProvider (Employees, Documents, Announcements, ...), each backed by its own query/
    // transaction (see toGroup) — without this, a single stuck/slow module (confirmed: Neon's
    // pooled endpoint can leave one connection's query running for minutes — see application.yml's
    // transaction.default-timeout comment) silently blocks every OTHER module's results too, even
    // though they're otherwise independent and most finish in milliseconds.
    private static final long PROVIDER_TIMEOUT_SECONDS = 8;

    private final List<SearchProvider> providers;
    private final UserRepository userRepo;
    // Not request-request-scoped — one small shared pool reused across all preview() calls.
    // toGroup() opens its own @Transactional (each on a separate thread/connection here), so
    // providers never contend over the request thread's transaction/connection.
    private final ExecutorService searchExecutor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "global-search-provider");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        searchExecutor.shutdownNow();
    }

    public SearchPreviewResponse preview(String actorEmail, String rawQuery) {
        String query = validate(rawQuery);
        User actor = requireUser(actorEmail);

        List<CompletableFuture<SearchGroupDto>> futures = providers.stream()
                .map(p -> CompletableFuture.supplyAsync(() -> toGroup(p, actor, query), searchExecutor)
                        .orTimeout(PROVIDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .exceptionally(ex -> {
                            log.warn("Search module '{}' timed out or failed for query '{}' — omitting it from results",
                                    p.moduleKey(), query, ex);
                            return null;
                        }))
                .toList();

        List<SearchGroupDto> groups = futures.stream()
                .map(CompletableFuture::join)
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
