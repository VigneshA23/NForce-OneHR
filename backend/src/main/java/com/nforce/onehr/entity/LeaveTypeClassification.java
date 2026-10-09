package com.nforce.onehr.entity;

/**
 * Valid values for {@link LeaveType#getClassification()} — mirrors the
 * {@code chk_leave_types_classification} check constraint (see
 * V182__add_classification_to_leave_types.sql). Plain string constants (not an enum), matching
 * {@link LeaveDurationType}'s convention, so they can be used directly as JPA column values
 * without a converter.
 */
public final class LeaveTypeClassification {

    public static final String PAID = "PAID";
    public static final String UNPAID = "UNPAID";

    private LeaveTypeClassification() {
    }
}
