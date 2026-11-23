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
import java.util.UUID;

/** Request/response shapes of the Leave Configuration endpoints (Organization Masters > Leave). */
public final class LeaveConfigDtos {

    private LeaveConfigDtos() {}

    public record EntitlementPolicyResponse(UUID id, Integer effectiveYear, BigDecimal days, String createdByName,
                                            LocalDateTime createdAt) {}

    /** A new entitlement from {@code effectiveYear}; existing balances change only if {@code applyToExistingBalances}. */
    public record EntitlementChangeRequest(
            @NotNull @Min(2000) @Max(2100) Integer effectiveYear,
            @NotNull @DecimalMin("0") @DecimalMax("999") BigDecimal days,
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

    public record YearEndRunResult(Integer fromYear, int balancesChecked, int transfersCreated,
                                   int expiriesUpdated, int failures) {}
}
