package com.nforce.onehr.dto.bench;

import com.nforce.onehr.entity.BenchReasonCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request/response shapes for the HR-facing bench reads and settings (US-A03/A04/A05/A08/A13). */
public final class BenchDtos {

    private BenchDtos() {}

    /** One benched employee as HR sees them: aging, skill match, and the skills themselves for export. */
    public record BenchListRow(UUID id, UUID employeeId, String employeeName, BenchReasonCode reasonCode,
                               LocalDate startDate, long daysOnBench, String agingBucket,
                               int matchScore, List<String> skills) {}

    public record DashboardPoint(LocalDate date, int benchedCount, int totalHeadcount) {}

    public record BucketCount(String bucket, long count) {}

    public record BenchDashboard(int benchedCount, int totalHeadcount, double benchPercent,
                                 List<BucketCount> buckets, List<DashboardPoint> trend) {}

    public record BucketInput(@NotBlank String label, @Min(0) int minDays, Integer maxDays) {}

    public record AgingConfigRequest(@NotEmpty @Valid List<BucketInput> buckets,
                                     @NotNull @Min(1) Integer notifyThresholdDays) {}

    public record AgingConfigResponse(List<BucketInput> buckets, int notifyThresholdDays) {}

    /** US-A06: an allocation an HR admin can link when ending an employee's bench. */
    public record AllocationOption(UUID id, String projectName, int capacityPercent, LocalDate startDate, LocalDate endDate) {}

    public record CostRow(String bandLabel, long benchedCount, BigDecimal avgCtc, BigDecimal totalCost) {}

    public record BenchCost(List<CostRow> rows, BigDecimal totalCost, long unbandedCount) {}
}
