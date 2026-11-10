package com.nforce.onehr.entity;

/** Valid {@link PayrollRunReviewItem#getDecision()} values for a {@link
 * PayrollRunReviewStepType#LEAVE_APPLIED} item. */
public final class LeaveReviewDecision {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    private LeaveReviewDecision() {
    }
}
