package com.nforce.onehr.dto.opportunity;

import com.nforce.onehr.entity.OpportunityStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** US-A10/A11/A12 shapes: internal opportunities, interest and closing. */
public final class OpportunityDtos {

    private OpportunityDtos() {}

    public record OpportunityRequest(@NotBlank String title, @NotNull @Min(1) Integer durationWeeks,
                                     @NotEmpty Set<UUID> requiredSkillIds) {}

    public record OpportunityResponse(UUID id, String title, int durationWeeks, OpportunityStatus status,
                                      List<String> requiredSkills, String postedBy, Instant createdAt,
                                      long interestCount, boolean interested, String assignedEmployee) {}

    public record InterestResponse(UUID employeeId, String employeeName, Instant expressedAt) {}

    public record CloseOpportunityRequest(@NotNull UUID employeeId, @NotNull UUID projectId,
                                          @NotNull @Min(1) @Max(100) Integer capacityPercent,
                                          @NotNull LocalDate startDate, @NotNull LocalDate endDate) {}
}
