package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/** Statutory Compliance page's own safety check: a Location with employees on it whose Country
 * doesn't match any currently-configured {@link com.nforce.onehr.entity.StatutoryRule} — exactly
 * the condition that makes {@code StatutoryDeductionService} silently skip PF/ESI/Professional
 * Tax for everyone at that Location, surfaced here instead of only showing up as a wrong payslip. */
@Getter
@Builder
public class LocationCountryMismatchResponse {
    private UUID locationId;
    private String locationName;
    private String country;
    private long employeeCount;
}
