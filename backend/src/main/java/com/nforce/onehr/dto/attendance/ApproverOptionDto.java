package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.util.UUID;

/** One selectable entry in the "assign to manager" dropdown on the request-creation form. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApproverOptionDto {

    private UUID userId;
    private String fullName;
    private String email;
    private String roleCode; // MANAGER | HR_ADMIN — Super Admin is excluded, see RegularizationService.ELIGIBLE_APPROVER_ROLES
}
