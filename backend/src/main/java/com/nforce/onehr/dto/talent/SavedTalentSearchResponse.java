package com.nforce.onehr.dto.talent;

import com.nforce.onehr.entity.SavedTalentSearch;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
public class SavedTalentSearchResponse {
    UUID id;
    String name;
    TalentSearchRequest query;
    Instant createdAt;

    public static SavedTalentSearchResponse from(SavedTalentSearch s, TalentSearchRequest query) {
        return new SavedTalentSearchResponse(s.getId(), s.getName(), query, s.getCreatedAt());
    }
}
