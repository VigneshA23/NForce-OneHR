package com.nforce.onehr.ai.dto;

import com.nforce.onehr.ai.entity.AiRateLimitSettings;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class AiRateLimitSettingsResponse {

    UUID id;
    boolean enabled;
    int requestsPerWindow;
    int windowMinutes;
    Instant updatedAt;

    public static AiRateLimitSettingsResponse from(AiRateLimitSettings settings) {
        return AiRateLimitSettingsResponse.builder()
                .id(settings.getId())
                .enabled(settings.isEnabled())
                .requestsPerWindow(settings.getRequestsPerWindow())
                .windowMinutes(settings.getWindowMinutes())
                .updatedAt(settings.getUpdatedAt())
                .build();
    }
}
