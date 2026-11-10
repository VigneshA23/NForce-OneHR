package com.nforce.onehr.entity;

/**
 * Valid values for {@link PayrollRunReviewItem#getSourceType()}. Only {@link
 * PayrollRunReviewStepType#LEAVE_APPLIED} items have a real backing record ({@code
 * PayrollRunReviewItem#getSourceId()} points at a {@code LeaveRequest} or {@code
 * AttendancePenalty}) — a {@link PayrollRunReviewStepType#NO_ATTENDANCE} item is always {@link
 * #NO_ATTENDANCE}, with a null sourceId, since nothing was filed for that day at all.
 */
public final class PayrollReviewItemSourceType {

    public static final String LEAVE_UNPAID = "LEAVE_UNPAID";
    public static final String PENALTY = "PENALTY";
    public static final String NO_ATTENDANCE = "NO_ATTENDANCE";

    private PayrollReviewItemSourceType() {
    }
}
