package com.nforce.onehr.entity;

/** Valid values for {@link SalaryHold#getHoldType()}. See {@link SalaryHold}'s own Javadoc for the
 * distinction Keka draws between the two. */
public final class SalaryHoldType {

    public static final String PROCESSING = "PROCESSING";
    public static final String PAYOUT = "PAYOUT";

    private SalaryHoldType() {
    }
}
