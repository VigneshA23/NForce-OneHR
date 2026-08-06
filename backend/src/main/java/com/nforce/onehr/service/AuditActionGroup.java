package com.nforce.onehr.service;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Finer-grained grouping than {@link AuditActionCategory}, backing the audit page's category
 * filter chips (Employee / Attendance / Leave / Expense / Asset / Access / Other). Derived by
 * action-string prefix so a new action string is automatically classified without a code change
 * to every call site — anything unrecognized falls into OTHER rather than disappearing.
 *
 * <p>Every action in {@link AuditActionCategory#ACCESS_CONTROL} maps to {@link #ACCESS} here and
 * vice versa, so the two taxonomies never disagree.
 */
public enum AuditActionGroup {
    EMPLOYEE,
    ATTENDANCE,
    LEAVE,
    EXPENSE,
    ASSET,
    ACCESS,
    OTHER;

    public static AuditActionGroup of(String action) {
        if (action == null) return OTHER;
        if (action.startsWith("EMPLOYEE_")) return EMPLOYEE;
        if (action.startsWith("ATTENDANCE_") || action.startsWith("WEB_CLOCK_IN")
                || action.startsWith("WEB_CLOCK_OUT") || action.startsWith("REGULARIZATION_")) return ATTENDANCE;
        if (action.startsWith("LEAVE_")) return LEAVE;
        if (action.startsWith("EXPENSE_")) return EXPENSE;
        if (action.startsWith("ASSET_")) return ASSET;
        if (action.startsWith("USER_") || action.startsWith("LOGIN_") || action.startsWith("PASSWORD_")) return ACCESS;
        return OTHER;
    }

    /** Every currently-known action string that falls into this group — used to build the chip-filter query. */
    public Set<String> knownActions() {
        return AuditActionCategory.allActions().stream()
                .filter(a -> of(a) == this)
                .collect(Collectors.toSet());
    }
}
