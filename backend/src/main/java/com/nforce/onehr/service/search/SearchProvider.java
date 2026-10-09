package com.nforce.onehr.service.search;

import com.nforce.onehr.dto.search.SearchResultItem;
import com.nforce.onehr.entity.User;

import java.util.List;

/**
 * Extension point for a module participating in global search. Each implementation is a plain
 * {@code @Component} picked up automatically by {@link GlobalSearchService} (Spring injects every
 * bean implementing this interface) — adding a future module to search means adding one more
 * implementation, never touching the search bar or this service.
 *
 * Every method receives the already-authenticated {@link User}, never a client-supplied id, and
 * must enforce (or delegate to a service that enforces) exactly the same authorization the
 * module's own existing list/detail endpoints do. No implementation may return a record the
 * actor couldn't already reach through that module directly.
 */
public interface SearchProvider {

    /** Stable key identifying this module, e.g. "EMPLOYEES". Used as the {@code /api/search/{module}} path segment. */
    String moduleKey();

    /** Human-readable group label for the search dropdown/results page, e.g. "Employees". */
    String moduleLabel();

    /** Best few matches for the header dropdown preview, plus the total match count. */
    SearchProviderResult preview(User actor, String query, int limit);

    /** One page of matches for the dedicated results page's independent per-module pagination. */
    SearchProviderResult page(User actor, String query, int page, int size);

    /** Route to this module's existing filtered list page for this query, or null if it has none. */
    String refineUrl(User actor, String query);

    record SearchProviderResult(List<SearchResultItem> items, long totalCount) {
        static final SearchProviderResult EMPTY = new SearchProviderResult(List.of(), 0);
    }
}
