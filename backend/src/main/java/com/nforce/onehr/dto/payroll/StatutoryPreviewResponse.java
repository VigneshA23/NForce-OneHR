package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** What {@link com.nforce.onehr.service.StatutoryDeductionService}'s formula alone would compute
 *  for this employee/rule/cycle right now — shown on the Statutory Override form so an admin can
 *  see what they're overriding FROM before typing a new value. Any existing override is
 *  deliberately ignored here (it's already visible in the overrides table on that same page).
 *  {@code computedAmount} is null when nothing can be computed — see {@code note} for why. */
@Getter
@Builder
public class StatutoryPreviewResponse {
    private BigDecimal computedAmount;
    private String note;
}
