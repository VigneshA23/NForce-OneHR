package com.nforce.onehr.dto.attendance;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * PN-06: penalty analytics for the caller's scope (the whole organisation for HR, the caller's
 * direct reports for a manager). Every figure is over the penalties that match the filters.
 * {@code standing} are the ones still in force (PENDING_REVIEW); {@code deductionDays} counts only
 * those, so a cancelled or reversed penalty never inflates what employees actually lose.
 */
@Data
@Builder
public class PenaltyAnalyticsResponse {

    private LocalDate from;
    private LocalDate to;
    /** ORGANISATION (HR) or TEAM (manager). */
    private String scope;
    private Summary summary;
    private List<Bucket> byRule;
    private List<Bucket> byDepartment;
    /** One bucket per calendar month in the range, oldest first, including months with none. */
    private List<Bucket> byMonth;
    /** Every employee with at least one matching penalty, worst first. */
    private List<EmployeeRow> byEmployee;
    /** Departments of everyone in scope, for the filter - independent of the department filter. */
    private List<String> departments;

    @Data
    @Builder
    public static class Summary {
        private int total;
        private int standing;
        private int cancelled;
        private int reversed;
        private BigDecimal deductionDays;
        private int employeesAffected;
    }

    @Data
    @Builder
    public static class Bucket {
        /** Rule code, department name or yyyy-MM. */
        private String key;
        private String label;
        private int total;
        private int standing;
        private int cancelled;
        private int reversed;
        private BigDecimal deductionDays;
    }

    @Data
    @Builder
    public static class EmployeeRow {
        private UUID employeeUserId;
        private String fullName;
        private String employeeCode;
        private String department;
        private int total;
        private int standing;
        private int cancelled;
        private int reversed;
        private BigDecimal deductionDays;
        /** Label of the rule behind most of this employee's standing penalties. */
        private String topRule;
    }
}
