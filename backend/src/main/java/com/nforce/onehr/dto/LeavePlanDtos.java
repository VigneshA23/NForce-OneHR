package com.nforce.onehr.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** LV-01: request/response shapes of the Leave Plan endpoints (Organization Masters > Leave). */
public final class LeavePlanDtos {

    private LeavePlanDtos() {}

    // ── Plans ──

    /** {@code employeeCount}: employees on the plan today (allocation, rule or default). */
    public record PlanSummary(UUID id, String name, String description, int leaveYearStartMonth, String status,
                              boolean orgDefault, Integer currentVersion, LocalDate currentVersionFrom,
                              int leaveTypeCount, long employeeCount, LocalDateTime updatedAt) {}

    /** PAST, CURRENT or SCHEDULED relative to today. */
    public record VersionSummary(UUID id, int version, LocalDate effectiveFrom, LocalDate effectiveTo, String status,
                                 String createdByName, LocalDateTime createdAt) {}

    public record PlanDetail(PlanSummary plan, List<VersionSummary> versions) {}

    /** One leave type inside a plan version. */
    public record TypeRuleDto(UUID leaveTypeId, String leaveTypeCode, String leaveTypeName, String classification,
                              String entitlementBasis, boolean leaveTypeActive, BigDecimal days, String creditFrequency,
                              boolean carryForwardEnabled, BigDecimal carryForwardMaxDays, Integer carryForwardExpiryMonths,
                              String carryForwardPriority, LeaveConfigDtos.EligibilityConfig eligibility) {}

    public record VersionDetail(UUID planId, String planName, VersionSummary version, List<TypeRuleDto> rules) {}

    /** One leave type's rules in a plan. {@code days} required for paid types, ignored for unpaid (unlimited). */
    public record TypeRuleRequest(
            @NotNull UUID leaveTypeId,
            @DecimalMin("0") @DecimalMax("999") BigDecimal days,
            @Pattern(regexp = "YEARLY|MONTHLY") String creditFrequency,
            boolean carryForwardEnabled,
            @DecimalMin("0") @DecimalMax("999") BigDecimal carryForwardMaxDays,
            @Min(1) @Max(24) Integer carryForwardExpiryMonths,
            @Pattern(regexp = "CARRY_FORWARD_FIRST|CURRENT_FIRST") String carryForwardPriority,
            @Valid LeaveConfigDtos.EligibilityConfig eligibility) {}

    public record CreatePlanRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @Min(1) @Max(12) Integer leaveYearStartMonth,
            @NotNull @Size(max = 100) @Valid List<TypeRuleRequest> rules) {}

    public record UpdatePlanRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @Min(1) @Max(12) Integer leaveYearStartMonth) {}

    /**
     * New rules from {@code effectiveFrom}; replaces the version starting that day if there is one.
     * {@code recreditCredited}: months already credited are re-credited at the new rules (otherwise only
     * months not credited yet follow them).
     */
    public record SaveVersionRequest(
            @NotNull LocalDate effectiveFrom,
            @NotNull @Size(max = 100) @Valid List<TypeRuleRequest> rules,
            boolean recreditCredited) {}

    public record ClonePlanRequest(@NotBlank @Size(max = 100) String name) {}

    // ── Allocation ──

    /** {@code status}: CURRENT, FUTURE or HISTORICAL relative to today. */
    public record AllocationDto(UUID id, UUID employeeUserId, UUID planId, String planName, LocalDate effectiveFrom,
                                LocalDate effectiveTo, String status, LocalDateTime createdAt) {}

    /** {@code source}: ALLOCATION, RULE or DEFAULT (or null when nothing applies). */
    public record EmployeePlanRow(UUID employeeUserId, String employeeCode, String fullName, boolean active,
                                  UUID departmentId, String departmentName, UUID locationId, String locationName,
                                  UUID planId, String planName, String source, String ruleName,
                                  AllocationDto currentAllocation, AllocationDto upcomingAllocation) {}

    public record EmployeePlanDetail(EmployeePlanRow row, List<AllocationDto> history) {}

    public record AllocateRequest(@NotNull UUID employeeUserId, @NotNull UUID planId, @NotNull LocalDate effectiveFrom,
                                  LocalDate effectiveTo) {}

    public record UpdateAllocationRequest(@NotNull UUID planId, @NotNull LocalDate effectiveFrom, LocalDate effectiveTo) {}

    public record BulkAllocateRequest(@NotEmpty @Size(max = 5000) List<UUID> employeeUserIds, @NotNull UUID planId,
                                      @NotNull LocalDate effectiveFrom, LocalDate effectiveTo) {}

    public record BulkRemoveRequest(@NotEmpty @Size(max = 5000) List<UUID> employeeUserIds) {}

    public record ConflictCheckRequest(@NotEmpty @Size(max = 5000) List<UUID> employeeUserIds, @NotNull LocalDate effectiveFrom,
                                       LocalDate effectiveTo, UUID excludeAllocationId) {}
}
