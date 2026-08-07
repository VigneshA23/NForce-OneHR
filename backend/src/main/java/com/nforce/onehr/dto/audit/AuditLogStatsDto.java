package com.nforce.onehr.dto.audit;

import lombok.*;

import java.util.Map;

/**
 * Backs the audit page's stat-card row. Always reflects the full filtered result set (not just
 * the current page), computed via cheap COUNT(*) queries rather than fetching every row.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLogStatsDto {

    private long totalCount;
    private long todayCount;
    /** Keys: EMPLOYEE, ATTENDANCE, LEAVE, EXPENSE, ASSET, ACCESS (Super Admin only), OTHER. */
    private Map<String, Long> byGroup;
}
