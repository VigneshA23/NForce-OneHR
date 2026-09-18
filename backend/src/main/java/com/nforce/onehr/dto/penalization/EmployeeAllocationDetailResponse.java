package com.nforce.onehr.dto.penalization;

import lombok.*;

import java.util.List;
import java.util.UUID;

/** Employee Detail view: profile summary + full effective-dated allocation history. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmployeeAllocationDetailResponse {
    private UUID employeeUserId;
    private String employeeCode;
    private String fullName;
    private String email;
    private boolean active;
    private String designationTitle;
    private String businessUnitName;
    private String departmentName;
    private String locationName;
    private UUID reportingManagerId;
    private String reportingManagerName;

    private UUID resolvedPolicyId;
    private String resolvedPolicyName;
    private String resolvedPolicySource;

    /** Newest first — every allocation row ever created for this employee, CURRENT/FUTURE/HISTORICAL alike. */
    private List<AllocationDto> history;
}
