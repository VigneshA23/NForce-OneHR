package com.nforce.onehr.entity;

/** Valid values for {@link AdHocDeduction#getStatus()}. */
public final class AdHocDeductionStatus {

    public static final String ACTIVE = "ACTIVE";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    private AdHocDeductionStatus() {
    }
}
