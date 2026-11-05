package com.nforce.onehr.entity;

/**
 * Valid values for {@link SalaryComponent#getCalcType()} — mirrors the
 * {@code chk_salary_components_calc_type} check constraint (see
 * V249__extend_salary_component_calculation_model.sql). Modeled directly on the org's current
 * payroll system (Keka): Basic is itself just a PERCENT_OF_GROSS component, HRA/Travel/etc. are
 * PERCENT_OF_COMPONENT against whichever component they're defined relative to (see
 * {@link SalaryComponent#getReferenceComponentId()}), and a structure typically has one
 * BALANCING_REMAINDER component ("Other Allowance = whatever's left") that absorbs the gap
 * between every other component and the assigned CTC.
 *
 * <p>There is no PERCENT_OF_BASIC here — that's just PERCENT_OF_COMPONENT where the referenced
 * component happens to be named "Basic". Collapsing it into one general mechanism, rather than
 * special-casing Basic, is what lets Travel be "20% of Basic" and HRA be "40% of Basic"
 * simultaneously without the structure caring which component is which.
 */
public final class SalaryCalculationType {

    public static final String FIXED = "FIXED";
    public static final String PERCENT_OF_GROSS = "PERCENT_OF_GROSS";
    public static final String PERCENT_OF_COMPONENT = "PERCENT_OF_COMPONENT";
    public static final String BALANCING_REMAINDER = "BALANCING_REMAINDER";

    private SalaryCalculationType() {
    }
}
