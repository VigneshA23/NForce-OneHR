package com.nforce.onehr.dto.search;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/** GET /api/search?q= — grouped top matches for the header search dropdown. */
@Value
@Builder
public class SearchPreviewResponse {
    String query;
    List<SearchGroupDto> groups;
}
