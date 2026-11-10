package com.nforce.onehr.entity;

/** Valid {@link PayrollRunReviewItem#getDecision()} values for a {@link
 * PayrollRunReviewStepType#NO_ATTENDANCE} item. */
public final class NoAttendanceReviewDecision {

    public static final String PENDING = "PENDING";
    public static final String DEDUCT_FROM_LEAVE = "DEDUCT_FROM_LEAVE";
    public static final String MARK_LOP = "MARK_LOP";
    public static final String NO_ACTION = "NO_ACTION";

    private NoAttendanceReviewDecision() {
    }
}
