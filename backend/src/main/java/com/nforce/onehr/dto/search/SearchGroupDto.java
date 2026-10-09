package com.nforce.onehr.dto.search;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/** A module's slice of the global search preview dropdown — top matches plus the full match count. */
@Value
@Builder
public class SearchGroupDto {
    String module;
    String label;
    List<SearchResultItem> items;
    long totalCount;
    /** Frontend route to the existing module list page, pre-filtered by this query — null if the module has none. */
    String refineUrl;
}
