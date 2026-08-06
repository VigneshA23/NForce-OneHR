package com.nforce.onehr.dto.onboarding;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data @Builder
public class TimelineEntryDto {
    private Instant at;
    private String text;
    private String meta;
}
