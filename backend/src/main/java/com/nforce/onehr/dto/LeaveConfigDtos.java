package com.nforce.onehr.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Request/response shapes of the Leave Configuration endpoints (Organization Masters > Leave). */
public final class LeaveConfigDtos {

    private LeaveConfigDtos() {}

    public record EntitlementPolicyResponse(UUID id, Integer effectiveYear, BigDecimal days, String creditFrequency,
                                            String createdByName, LocalDateTime createdAt) {}

    /** A new entitlement from {@code effectiveYear}; existing balances change only if {@code applyToExistingBalances}. */
    public record EntitlementChangeRequest(
            @NotNull @Min(2000) @Max(2100) Integer effectiveYear,
            @NotNull @DecimalMin("0") @DecimalMax("999") BigDecimal days,
            /** YEARLY (all at once) or MONTHLY; null keeps the frequency in force for that year. */
            @Pattern(regexp = "YEARLY|MONTHLY") String creditFrequency,
            boolean applyToExistingBalances) {}

    public record CarryForwardSettingsRequest(
            boolean enabled,
            @DecimalMin("0") @DecimalMax("999") BigDecimal maxDays,
            @Min(1) @Max(24) Integer expiryMonths,
            @Pattern(regexp = "CARRY_FORWARD_FIRST|CURRENT_FIRST") String priority) {}

    /** Manual adjustment of one balance row; the reason is mandatory and audited with before/after. */
    public record BalanceAdjustmentRequest(
            @NotNull @DecimalMin("0") @DecimalMax("999") BigDecimal entitlementDays,
            @NotNull @DecimalMin("0") @DecimalMax("999") BigDecimal carriedForwardDays,
            @NotBlank @Size(max = 500) String reason) {}

    public record CarryForwardTransferResponse(UUID id, UUID employeeUserId, String employeeName, String employeeCode,
                                               String leaveTypeCode, String leaveTypeName, Integer fromYear,
                                               BigDecimal unusedDays, BigDecimal carriedDays, BigDecimal lapsedDays,
                                               LocalDate expiresOn, LocalDateTime processedAt, LocalDateTime updatedAt) {}

    /** Who a leave type applies to (V314). Empty lists = no restriction of that kind. */
    /**
     * Who a type in a plan applies to. LV-06: {@code maritalStatuses}, {@code grades} (designation grade); and,
     * checked on the leave's start date, {@code minTenureMonths} of service and {@code allowedDuringProbation}
     * (null = true). Hand-picked includes bypass every condition; excludes always win.
     */
    public record EligibilityConfig(
            @Size(max = 10) List<String> genders,
            @Size(max = 500) List<String> departmentIds,
            @Size(max = 500) List<String> locationIds,
            @Size(max = 20) List<String> employmentTypes,
            @Size(max = 5000) List<UUID> includeEmployeeIds,
            @Size(max = 5000) List<UUID> excludeEmployeeIds,
            @Size(max = 10) List<String> maritalStatuses,
            @Size(max = 100) List<String> grades,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(600) Integer minTenureMonths,
            Boolean allowedDuringProbation) {

        /** Without the LV-06 criteria. */
        public EligibilityConfig(List<String> genders, List<String> departmentIds, List<String> locationIds,
                                 List<String> employmentTypes, List<UUID> includeEmployeeIds, List<UUID> excludeEmployeeIds) {
            this(genders, departmentIds, locationIds, employmentTypes, includeEmployeeIds, excludeEmployeeIds,
                    null, null, null, null);
        }
    }

    public record EligibilityPreview(int eligibleCount, int totalCount) {}

    /** One month's credit, for the balance's credit history. */
    public record AccrualCreditResponse(Integer year, Integer month, BigDecimal days, String source, LocalDateTime creditedAt) {}

    /** {@code failedEmployees}: codes of the employees whose crediting failed (retried on the next run). */
    public record AccrualRunResult(Integer year, int employeesChecked, int creditsAdded, int failures,
                                   List<String> failedEmployees, int creditsPaused) {

        public AccrualRunResult(Integer year, int employeesChecked, int creditsAdded, int failures, List<String> failedEmployees) {
            this(year, employeesChecked, creditsAdded, failures, failedEmployees, 0);
        }
    }

    /**
     * LV-03 dry run: one credit a run would post. {@code months} 1-12 credited together; {@code status} CREDIT, or
     * PAUSED with {@code reason}.
     */
    public record AccrualPreviewLine(UUID employeeUserId, String employeeName, String employeeCode, UUID leaveTypeId,
                                     String leaveTypeName, int year, List<Integer> months, BigDecimal days, LocalDate creditDate,
                                     String frequency, String status, String reason) {}

    /** {@code employees}: employees with something due; {@code creditDays}: days that would be credited (paused excluded). */
    public record AccrualPreview(LocalDate asOf, int employees, BigDecimal creditDays, int paused, List<AccrualPreviewLine> lines) {}

    /** {@code failedEmployees}: codes of the employees whose carry-forward or expiry failed (retried on the next run). */
    public record YearEndRunResult(Integer fromYear, int balancesChecked, int transfersCreated,
                                   int expiriesUpdated, int failures, List<String> failedEmployees) {}
}
