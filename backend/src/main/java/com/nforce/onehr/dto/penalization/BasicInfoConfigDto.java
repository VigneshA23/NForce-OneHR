package com.nforce.onehr.dto.penalization;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * Section 7-9: fields that apply to the whole Penalization Policy document, not one section —
 * deduction method (+ leave priority cascade), buffer period, notice-period override.
 */
@Data
public class BasicInfoConfigDto {

    @NotNull
    @Pattern(regexp = "LOSS_OF_PAY|PAID_LEAVE", message = "Deduction method must be LOSS_OF_PAY or PAID_LEAVE")
    private String deductionMethod = "LOSS_OF_PAY";

    /** LeaveType.code values in cascade priority order, e.g. ["SICK", "CASUAL", "PAID"]. Only
     * meaningful (and required to be non-empty) when deductionMethod is PAID_LEAVE — enforced in
     * PenalizationPolicyService, not here, since it's a cross-field rule. */
    private List<String> leavePriorityOrder = List.of();

    @Min(value = 0, message = "Buffer period days cannot be negative")
    private Integer bufferPeriodDays;

    /** "If employee is under notice period, consider all penalties as Loss of Pay." */
    private boolean noticePeriodForcesLopEnabled;

    /**
     * Section 15: an admin-chosen future effective date for the version being saved. Null (the
     * default) preserves the original behavior — effective the 1st of the calendar month after
     * the save date. When provided it must be a genuine future date; see
     * {@code PenalizationPolicyService#validateBasicInfo}. Not echoed back on the response's own
     * {@code basicInfo} — the version's actual resolved effective date is
     * {@link PenalizationPolicyResponse#getEffectiveFrom()} at the top level.
     */
    private LocalDate requestedEffectiveFrom;
}
