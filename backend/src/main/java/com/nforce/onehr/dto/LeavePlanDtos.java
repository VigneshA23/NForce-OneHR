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
                              int leaveTypeCount, long employeeCount, LocalDateTime updatedAt,
                              Proration proration) {}

    /**
     * LV-04: how the plan prorates a part-year entitlement. {@code method} MONTHS (whole months; with
     * {@code cutoffDay}, joining after it skips the joining month and leaving before it skips the exit month) or
     * CALENDAR_DAYS (the days served in those months); {@code rounding} NEAREST_HALF, DOWN_HALF, UP_HALF or NONE.
     * Null fields = the defaults (MONTHS, no cut-off, NEAREST_HALF).
     */
    public record Proration(
            @Pattern(regexp = "MONTHS|CALENDAR_DAYS") String method,
            @Min(1) @Max(31) Integer cutoffDay,
            @Pattern(regexp = "NEAREST_HALF|DOWN_HALF|UP_HALF|NONE") String rounding) {}

    /** PAST, CURRENT or SCHEDULED relative to today. */
    public record VersionSummary(UUID id, int version, LocalDate effectiveFrom, LocalDate effectiveTo, String status,
                                 String createdByName, LocalDateTime createdAt) {}

    public record PlanDetail(PlanSummary plan, List<VersionSummary> versions) {}

    /** One leave type inside a plan version. */
    public record TypeRuleDto(UUID leaveTypeId, String leaveTypeCode, String leaveTypeName, String classification,
                              String entitlementBasis, boolean leaveTypeActive, BigDecimal days, String creditFrequency,
                              boolean carryForwardEnabled, BigDecimal carryForwardMaxDays, Integer carryForwardExpiryMonths,
                              String carryForwardPriority, LeaveConfigDtos.EligibilityConfig eligibility, RuleLimits limits) {}

    /**
     * LV-02: how a type may be requested under the plan. Null numbers = no limit. {@code entitlementMode}: QUOTA
     * (the days above), UNLIMITED (paid, nothing deducted) or EARNED_ONLY (nothing credited automatically).
     * {@code attachmentAllowedTypes}: file extensions, e.g. "pdf,jpg"; blank = any. {@code notNextToTypeIds}: leave
     * types that may not be taken directly before or after this one (only weekly offs/holidays in between).
     * LV-07 sandwich: {@code sandwichEnabled} counts weekly offs / holidays between leave days;
     * {@code sandwichAppliesTo} HOLIDAYS, WEEK_OFFS or BOTH; {@code sandwichMinDays} only when the leave covers at
     * least that many working days; {@code sandwichAcrossRequests} also between adjacent requests of the type.
     */
    public record RuleLimits(
            @Pattern(regexp = "QUOTA|UNLIMITED|EARNED_ONLY") String entitlementMode,
            @DecimalMin("0.5") @DecimalMax("366") BigDecimal minDaysPerRequest,
            @DecimalMin("0.5") @DecimalMax("366") BigDecimal maxDaysPerRequest,
            @Min(1) @Max(31) Integer maxRequestsPerMonth,
            @Min(1) @Max(366) Integer maxRequestsPerYear,
            @DecimalMin("0.5") @DecimalMax("366") BigDecimal maxConsecutiveDays,
            @Min(1) @Max(365) Integer noticeDays,
            @DecimalMin("0") @DecimalMax("366") BigDecimal noticeAppliesOverDays,
            Boolean allowHalfDay,
            boolean allowHourly,
            @DecimalMin("0.5") @DecimalMax("23.5") BigDecimal minHours,
            @DecimalMin("0") @DecimalMax("366") BigDecimal attachmentRequiredOverDays,
            @Size(max = 200) String attachmentAllowedTypes,
            @Min(1) @Max(10) Integer attachmentMaxMb,
            @Size(max = 100) List<UUID> notNextToTypeIds,
            boolean sandwichEnabled,
            @Pattern(regexp = "HOLIDAYS|WEEK_OFFS|BOTH") String sandwichAppliesTo,
            @DecimalMin("0.5") @DecimalMax("366") BigDecimal sandwichMinDays,
            boolean sandwichAcrossRequests) {

        /** No limits: quota, half days allowed, no sandwich, nothing else. */
        public static final RuleLimits DEFAULTS = new RuleLimits("QUOTA", null, null, null, null, null, null, null,
                true, false, null, null, null, null, List.of());

        /** Without a sandwich rule (LV-02 shape). */
        public RuleLimits(String entitlementMode, BigDecimal minDaysPerRequest, BigDecimal maxDaysPerRequest,
                          Integer maxRequestsPerMonth, Integer maxRequestsPerYear, BigDecimal maxConsecutiveDays,
                          Integer noticeDays, BigDecimal noticeAppliesOverDays, Boolean allowHalfDay, boolean allowHourly,
                          BigDecimal minHours, BigDecimal attachmentRequiredOverDays, String attachmentAllowedTypes,
                          Integer attachmentMaxMb, List<UUID> notNextToTypeIds) {
            this(entitlementMode, minDaysPerRequest, maxDaysPerRequest, maxRequestsPerMonth, maxRequestsPerYear,
                    maxConsecutiveDays, noticeDays, noticeAppliesOverDays, allowHalfDay, allowHourly, minHours,
                    attachmentRequiredOverDays, attachmentAllowedTypes, attachmentMaxMb, notNextToTypeIds,
                    false, "BOTH", null, false);
        }
    }

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
            @Valid LeaveConfigDtos.EligibilityConfig eligibility,
            @Valid RuleLimits limits) {

        /** Without LV-02 limits (= {@link RuleLimits#DEFAULTS}). */
        public TypeRuleRequest(UUID leaveTypeId, BigDecimal days, String creditFrequency, boolean carryForwardEnabled,
                               BigDecimal carryForwardMaxDays, Integer carryForwardExpiryMonths, String carryForwardPriority,
                               LeaveConfigDtos.EligibilityConfig eligibility) {
            this(leaveTypeId, days, creditFrequency, carryForwardEnabled, carryForwardMaxDays, carryForwardExpiryMonths,
                    carryForwardPriority, eligibility, null);
        }
    }

    public record CreatePlanRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @Min(1) @Max(12) Integer leaveYearStartMonth,
            @NotNull @Size(max = 100) @Valid List<TypeRuleRequest> rules,
            @Valid Proration proration) {

        public CreatePlanRequest(String name, String description, Integer leaveYearStartMonth, List<TypeRuleRequest> rules) {
            this(name, description, leaveYearStartMonth, rules, null);
        }
    }

    /** {@code proration} null = leave it as it is. */
    public record UpdatePlanRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @Min(1) @Max(12) Integer leaveYearStartMonth,
            @Valid Proration proration) {

        public UpdatePlanRequest(String name, String description, Integer leaveYearStartMonth) {
            this(name, description, leaveYearStartMonth, null);
        }
    }

    // ── Exit settlements (LV-04) ──

    /** One leave type's position for a leaver; {@code recoveryDays} > 0 = leave taken beyond the entitlement. */
    public record ExitSettlementDto(UUID employeeUserId, String employeeName, String employeeCode, LocalDate lastWorkingDay,
                                    UUID leaveTypeId, String leaveTypeName, int year, BigDecimal entitledDays,
                                    BigDecimal carriedForwardDays, BigDecimal usedDays, BigDecimal encashableDays,
                                    BigDecimal recoveryDays, LocalDateTime computedAt) {}

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
