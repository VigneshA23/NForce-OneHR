package com.nforce.onehr.dto.compoff;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Request/response shapes for Comp-Off. */
public final class CompOffDtos {
    private CompOffDtos() {}

    public record SettingsDto(boolean overtimeEnabled, boolean compOffEnabled, boolean eligibleWeeklyOff,
                              boolean eligibleHoliday, boolean includeWeekdayExtra, int minMinutesHalfDay,
                              int minMinutesFullDay, int validityDays, boolean requireCreditApproval,
                              boolean allowPartialDay, int expiryNoticeDays, LocalDateTime updatedAt) {}

    /** Eligibility rules, thresholds and validity - HR/Admin (leave:CONFIGURE). Never touches the OT/Comp-Off switch. */
    public record UpdateRulesRequest(
            boolean eligibleWeeklyOff,
            boolean eligibleHoliday,
            boolean includeWeekdayExtra,
            @Min(1) int minMinutesHalfDay,
            @Min(1) int minMinutesFullDay,
            @Min(1) int validityDays,
            boolean requireCreditApproval,
            boolean allowPartialDay,
            @Min(0) int expiryNoticeDays) {}

    /** The company policy switch - Super Admin only (compensation_policy:CONFIGURE). */
    public record UpdatePolicyRequest(boolean overtimeEnabled, boolean compOffEnabled) {}

    /** What every signed-in user may know about the policy. */
    public record PolicyDto(boolean overtimeEnabled, boolean compOffEnabled, boolean allowPartialDay) {}

    public record DetectRequest(@NotNull LocalDate from, @NotNull LocalDate to, List<UUID> employeeIds) {}

    public record SkippedRecord(UUID employeeUserId, String employeeName, LocalDate workDate, String reason) {}

    public record DetectResult(int created, int autoCredited, int unchanged, List<SkippedRecord> skipped) {}

    public record ClaimDto(UUID id, UUID employeeUserId, String employeeCode, String employeeName, LocalDate workDate,
                           String dayType, int workedMinutes, int eligibleMinutes, BigDecimal creditDays,
                           String choice, String creditStatus, LocalDate expiresOn) {}

    public record ChooseRequest(@NotNull @Pattern(regexp = "OT|COMP_OFF") String choice) {}

    public record CreditDto(UUID id, UUID employeeUserId, String employeeCode, String employeeName,
                            String departmentName, String source, LocalDate workDate, BigDecimal creditDays,
                            BigDecimal usedDays, BigDecimal deductedDays, BigDecimal expiredDays,
                            BigDecimal remainingDays, String status, LocalDate earnedOn, LocalDate expiresOn,
                            String reason, String reviewComment) {}

    public record DecisionRequest(@NotNull @Pattern(regexp = "APPROVE|REJECT") String decision, String comment) {}

    /** Positive days add a credit; negative days deduct from the employee's available credits. */
    public record AdjustRequest(@NotNull UUID employeeUserId, @NotNull BigDecimal days, @NotBlank String reason) {}

    public record BalanceDto(BigDecimal earned, BigDecimal available, BigDecimal utilized, BigDecimal pending,
                             BigDecimal expired, BigDecimal adjusted) {}

    public record EventDto(UUID id, String event, BigDecimal days, String note, String actorName,
                           LocalDateTime createdAt) {}

    public record MyCompOff(PolicyDto policy, BalanceDto balance, List<ClaimDto> claims, List<CreditDto> credits,
                            List<EventDto> history) {}

    public record SummaryRow(UUID employeeUserId, String employeeCode, String employeeName, String departmentName,
                             String managerName, BigDecimal earned, BigDecimal available, BigDecimal utilized,
                             BigDecimal pending, BigDecimal expired, BigDecimal adjusted,
                             UUID departmentId, UUID managerUserId) {}

    public record DepartmentRow(String departmentName, int employees, BigDecimal earned, BigDecimal available,
                                BigDecimal utilized, BigDecimal pending, BigDecimal expired, BigDecimal adjusted) {}

    public record Report(List<SummaryRow> employees, List<DepartmentRow> departments) {}
}
