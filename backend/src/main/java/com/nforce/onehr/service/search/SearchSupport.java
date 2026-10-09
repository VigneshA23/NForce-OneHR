package com.nforce.onehr.service.search;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** Small helpers shared by the in-memory {@link SearchProvider} implementations. */
public final class SearchSupport {

    private SearchSupport() {
    }

    /** Slices an already-filtered/sorted in-memory list the same way a DB Pageable would. */
    public static <T> List<T> paginate(List<T> items, int page, int size) {
        if (size <= 0) return Collections.emptyList();
        int from = Math.max(0, page) * size;
        if (from >= items.size()) return Collections.emptyList();
        int to = Math.min(items.size(), from + size);
        return items.subList(from, to);
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
