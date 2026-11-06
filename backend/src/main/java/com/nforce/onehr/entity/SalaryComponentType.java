package com.nforce.onehr.entity;

/**
 * Valid values for {@link SalaryComponent#getType()} — mirrors the
 * {@code chk_salary_component_catalog_type} check constraint (see
 * V251__add_salary_component_catalog.sql). Matches US-C20's exact catalog model: Earning/
 * Deduction split into a plain (recurring, part of a Salary Structure — Basic, HRA) and an
 * ad-hoc (one-off, applied to a specific payroll run — OT, bonus, asset-damage recovery) variant.
 * Reimbursement has no ad-hoc counterpart — a reimbursement is inherently a one-time claim, so
 * the recurring/ad-hoc split doesn't apply to it.
 */
public final class SalaryComponentType {

    public static final String EARNING = "EARNING";
    public static final String AD_HOC_EARNING = "AD_HOC_EARNING";
    public static final String DEDUCTION = "DEDUCTION";
    public static final String AD_HOC_DEDUCTION = "AD_HOC_DEDUCTION";
    public static final String REIMBURSEMENT = "REIMBURSEMENT";

    private SalaryComponentType() {
    }
}
