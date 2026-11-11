package com.nforce.onehr.entity;

/** Valid values for {@link Arrears#getSourceType()}. */
public final class ArrearsSourceType {
    public static final String SALARY_REVISION = "SALARY_REVISION";
    public static final String LOP_REVERSAL = "LOP_REVERSAL";
    public static final String HOLD_RELEASE = "HOLD_RELEASE";

    private ArrearsSourceType() {
    }
}
