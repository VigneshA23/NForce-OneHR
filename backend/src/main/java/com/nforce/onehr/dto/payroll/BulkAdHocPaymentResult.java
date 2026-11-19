package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** Result of a bulk ad-hoc payment add — see AdHocPaymentService#bulkAdd. {@code skippedCount} is
 *  employees who already had this exact component name added in this run (the bulk action is
 *  therefore safe to click again without doubling everyone up), never a validation failure. */
@Getter
@Builder
public class BulkAdHocPaymentResult {
    private List<AdHocPaymentResponse> added;
    private int skippedCount;
}
