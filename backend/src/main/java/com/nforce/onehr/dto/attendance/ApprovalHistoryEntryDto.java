package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApprovalHistoryEntryDto {

    private String actionType; // APPROVED | REJECTED
    private String actorName;
    private String comments;
    private LocalDateTime actionDate;
}
