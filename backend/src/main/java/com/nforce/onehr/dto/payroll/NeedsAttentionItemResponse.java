package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

/** One employee flagged on the Pre-Approval Summary's "Needs Attention" count, with the specific
 *  reason(s) — so a reviewer isn't just shown a number with no way to act on it. */
@Getter
@Builder
public class NeedsAttentionItemResponse {
    private UUID employeeId;
    private String employeeName;
    private List<String> reasons;
}
