package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApprovalHistoryEntryDto {

    private String actionType; // APPROVED | REJECTED
    private String actorName;
    private String actorRole; // MANAGER | HR_ADMIN | SUPER_ADMIN — authority exercised for this action
    private String comments;
    private LocalDateTime actionDate;
}
