package com.nforce.onehr.dto.overtime;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Request/response shapes for the overtime management module. */
public final class OtDtos {
    private OtDtos() {}

    public record SettingsDto(int minOtMinutes, int roundingMinutes, BigDecimal workingDaysPerMonth,
                              boolean requireApprovedRequest, int maxRequestMinutes,
                              boolean allowDuplicateRequests, boolean adjustmentApprovalRequired,
                              LocalDateTime updatedAt) {}

    public record UpdateSettingsRequest(
            @Min(0) int minOtMinutes,
            @Min(1) int roundingMinutes,
            @NotNull @DecimalMin("1") BigDecimal workingDaysPerMonth,
            boolean requireApprovedRequest,
            @Min(1) int maxRequestMinutes,
            boolean allowDuplicateRequests,
            boolean adjustmentApprovalRequired) {}

    public record RateRuleRequest(
            @NotBlank String name,
            @NotNull @Pattern(regexp = "ANY|WEEKDAY|WEEKLY_OFF|HOLIDAY") String dayType,
            UUID departmentId,
            UUID shiftId,
            @NotNull @DecimalMin(value = "0.01") BigDecimal multiplier,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Boolean active) {}

    public record RateRuleDto(UUID id, String name, String dayType, UUID departmentId, String departmentName,
                              UUID shiftId, String shiftName, BigDecimal multiplier, LocalDate effectiveFrom,
                              LocalDate effectiveTo, boolean active) {}

    public record RunRequest(@NotNull LocalDate from, @NotNull LocalDate to, List<UUID> employeeIds) {}

    public record SkippedRecord(UUID employeeUserId, String employeeName, LocalDate workDate, String reason) {}

    public record RunResult(int created, int updated, int unchanged, List<SkippedRecord> skipped) {}

    public record CalculationDto(UUID id, UUID employeeUserId, String employeeCode, String employeeName,
                                 String departmentName, String shiftName, LocalDate workDate,
                                 int scheduledMinutes, int workedMinutes, int otMinutes, String dayType,
                                 BigDecimal multiplier, BigDecimal hourlyRate, BigDecimal otAmount,
                                 String status, boolean adjusted, boolean hasPendingAdjustment,
                                 String reviewComment, UUID payrollRunId, LocalDateTime calculatedAt) {}

    public record DecisionRequest(@NotEmpty List<UUID> ids, @NotNull @Pattern(regexp = "APPROVE|REJECT") String decision,
                                  String comment) {}

    public record HistoryDto(UUID id, String event, Integer workedMinutes, Integer otMinutes,
                             BigDecimal multiplier, BigDecimal hourlyRate, BigDecimal otAmount, String status,
                             String note, String actorName, LocalDateTime createdAt) {}

    public record AdjustRequest(Integer otMinutes, BigDecimal otAmount, @NotBlank String reason) {}

    public record AdjustmentDto(UUID id, UUID calculationId, String employeeName, LocalDate workDate,
                                int originalMinutes, BigDecimal originalAmount, int newMinutes,
                                BigDecimal newAmount, String reason, String status, String requestedByName,
                                LocalDateTime requestedAt, String decidedByName, LocalDateTime decidedAt,
                                String decisionComment) {}

    public record AdjustmentDecisionRequest(@NotNull @Pattern(regexp = "APPROVE|REJECT") String decision,
                                            String comment) {}

    public record PayrollOtRow(UUID employeeUserId, String employeeCode, String employeeName, LocalDate workDate,
                               BigDecimal hours, BigDecimal hourlyRate, BigDecimal multiplier,
                               BigDecimal amount) {}

    public record PayrollOtEmployee(UUID employeeUserId, String employeeCode, String employeeName,
                                    BigDecimal approvedAmount, BigDecimal payslipAmount, boolean discrepancy,
                                    List<PayrollOtRow> rows) {}

    public record PayrollOtSummary(UUID runId, LocalDate cycleStart, LocalDate cycleEnd,
                                   BigDecimal totalTransferred, BigDecimal totalApprovedInCycle,
                                   List<PayrollOtEmployee> employees) {}
}
