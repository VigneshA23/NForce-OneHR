package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * US-C10 (Keka-walkthrough amendments, 4 Nov 2026) — everything an HR Admin/Finance reviewer
 * should see before approving a run: this cycle's totals next to the previous finalized cycle's
 * (null if there is none yet — e.g. the very first run for a pay group), status counts, and the
 * per-employee variance list with likely drivers. The hard reconciliation block itself (a mismatch
 * between stored totals and the sum of line items) already exists on {@code submit()} — this
 * endpoint is the human-readable summary layered on top of that, not a replacement for it.
 */
@Getter
@Builder
public class PreApprovalSummaryResponse {
    private PeriodTotalsResponse current;
    /** Null when this is the first run ever generated for this pay group. */
    private PeriodTotalsResponse previous;
    private int processedCount;
    private int onHoldCount;
    private int needsAttentionCount;
    /** Who, and exactly why — one entry per flagged employee, reasons listed in plain language. */
    private List<NeedsAttentionItemResponse> needsAttentionDetails;
    private List<EmployeeVarianceResponse> variances;
}
