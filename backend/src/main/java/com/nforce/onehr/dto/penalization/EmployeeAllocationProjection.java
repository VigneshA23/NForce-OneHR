package com.nforce.onehr.dto.penalization;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

/**
 * Purpose-built projection backing {@link com.nforce.onehr.service.PenalizationPolicyAllocationService
 * #searchEmployees} row hydration — only the scalar Employee/User/BusinessUnit/Department/
 * Designation/PenalisationPolicy columns the Allocation table actually reads, selected directly by
 * a JPQL constructor expression instead of fetching full JPA entities (and their lazy-association
 * graphs) via {@code JOIN FETCH}. {@code legacyPolicyId} is the raw FK — just enough to tell
 * "LEGACY" from "DEFAULT" in {@code resolvedPolicySource} without loading the whole
 * {@code PenalisationPolicy} row.
 */
@Getter
@AllArgsConstructor
public class EmployeeAllocationProjection {
    private final UUID employeeUserId;
    private final String employeeCode;
    private final String fullName;
    private final String email;
    private final boolean active;
    private final String designationTitle;
    private final UUID businessUnitId;
    private final String businessUnitName;
    private final UUID departmentId;
    private final String departmentName;
    private final UUID locationId;
    private final String locationName;
    private final UUID legacyPolicyId;
}
