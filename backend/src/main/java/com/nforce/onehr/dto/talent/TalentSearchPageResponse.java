package com.nforce.onehr.dto.talent;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class TalentSearchPageResponse {
    List<TalentSearchResultItem> items;
    int page;
    int size;
    long totalElements;
    int totalPages;
}
