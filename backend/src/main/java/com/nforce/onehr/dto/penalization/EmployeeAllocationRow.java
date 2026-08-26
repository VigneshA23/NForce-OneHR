package com.nforce.onehr.dto.penalization;

import lombok.*;

import java.util.UUID;

/** One row of the Penalization Policy Allocation employee search/list. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmployeeAllocationRow {
    private UUID employeeUserId;
    private String employeeCode;
    private String fullName;
    private String email;
    private boolean active;
    private String designationTitle;

    private UUID businessUnitId;
    private String businessUnitName;
    private UUID departmentId;
    private String departmentName;
    private UUID locationId;
    private String locationName;

    private UUID reportingManagerId;
    private String reportingManagerName;

    /** The policy actually governing this employee today, and where that resolution came from. */
    private UUID resolvedPolicyId;
    private String resolvedPolicyName;
    /** ALLOCATION / LEGACY / DEFAULT — same three-tier priority as PenalizationPolicyResolutionService. */
    private String resolvedPolicySource;

    /** The allocation row backing resolvedPolicy* above, when resolvedPolicySource == ALLOCATION. */
    private AllocationDto currentAllocation;
    /** The next scheduled allocation row after today, if one exists (e.g. "policy B starting tomorrow"). */
    private AllocationDto upcomingAllocation;
}
