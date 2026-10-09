package com.nforce.onehr.dto.search;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/** GET /api/search/{module}?q=&page=&size= — one module's independently-paginated results page. */
@Value
@Builder
public class SearchPageResponse {
    String module;
    String label;
    List<SearchResultItem> items;
    int page;
    int size;
    long totalElements;
    int totalPages;
    String refineUrl;
}
