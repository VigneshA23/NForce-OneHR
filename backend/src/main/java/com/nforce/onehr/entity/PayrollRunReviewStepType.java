package com.nforce.onehr.entity;

/** Valid values for {@link PayrollRunReviewItem#getStepType()} — US-C22/US-C23's two run steps. */
public final class PayrollRunReviewStepType {

    public static final String LEAVE_APPLIED = "LEAVE_APPLIED";
    public static final String NO_ATTENDANCE = "NO_ATTENDANCE";

    private PayrollRunReviewStepType() {
    }
}
