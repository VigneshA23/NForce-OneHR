package com.nforce.onehr.dto.search;

import lombok.Builder;
import lombok.Value;

/** One matched record within a module's group, ready for the frontend to render and navigate to. */
@Value
@Builder
public class SearchResultItem {
    String module;
    String id;
    String title;
    String subtitle;
    /** Frontend route (relative, e.g. "/directory?userId=...") for this record's existing detail view. */
    String detailUrl;
}
