package com.nforce.onehr.entity;

/**
 * Valid values for {@link LeaveRequest#getDurationType()} — mirrors the
 * {@code chk_leave_requests_duration_type} check constraint (see V140__add_leave_duration_type.sql).
 * Plain string constants (not an enum) so they can be used directly as {@code switch} case labels
 * and JPA column values without a converter.
 */
public final class LeaveDurationType {

    public static final String FULL_DAY = "FULL_DAY";
    public static final String HALF_DAY = "HALF_DAY";
    public static final String QUARTER_DAY = "QUARTER_DAY";
    public static final String HOURLY = "HOURLY";

    private LeaveDurationType() {
    }
}
