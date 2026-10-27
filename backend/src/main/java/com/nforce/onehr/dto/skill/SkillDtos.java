package com.nforce.onehr.dto.skill;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** US-A07 shapes: the managed taxonomy and per-employee 1-5 ratings. */
public final class SkillDtos {

    private SkillDtos() {}

    public record SkillRequest(@NotBlank String skill, @NotBlank String category) {}

    public record SkillResponse(UUID id, String skill, String category) {}

    public record RatingRequest(@NotNull UUID skillId, @NotNull @Min(1) @Max(5) Integer rating) {}

    public record RatingResponse(UUID skillId, String skill, String category, int selfRating, Integer managerRating) {}

    public record TeamRatingResponse(UUID employeeId, String employeeName, UUID skillId, String skill,
                                     int selfRating, Integer managerRating) {}
}
