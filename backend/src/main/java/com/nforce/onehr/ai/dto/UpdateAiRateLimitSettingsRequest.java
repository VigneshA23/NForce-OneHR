package com.nforce.onehr.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Bounds mirror {@code AiRateLimitSettingsService}'s own validation and V192's DB-level CHECKs —
 * validated here too so a bad value is rejected with a normal 400 before it ever reaches the
 * service/DB layer, same belt-and-suspenders as {@code UpdateAttendanceRulesRequest}.
 */
@Data
public class UpdateAiRateLimitSettingsRequest {

    @NotNull(message = "enabled is required")
    private Boolean enabled;

    @NotNull(message = "requestsPerWindow is required")
    @Min(value = 1, message = "requestsPerWindow must be at least 1")
    @Max(value = 1000, message = "requestsPerWindow must be at most 1000")
    private Integer requestsPerWindow;

    @NotNull(message = "windowMinutes is required")
    @Min(value = 1, message = "windowMinutes must be at least 1")
    @Max(value = 1440, message = "windowMinutes must be at most 1440 (24 hours)")
    private Integer windowMinutes;
}
