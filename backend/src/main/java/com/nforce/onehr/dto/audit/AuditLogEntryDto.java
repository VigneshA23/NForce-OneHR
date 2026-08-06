package com.nforce.onehr.dto.audit;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLogEntryDto {

    private Long id;
    private UUID actorId;
    private String actorName;      // Employee.fullName if resolvable, else null
    private String actorEmail;     // User.email, populated whenever the actor still exists
    private String action;
    private String actionCategory; // AuditActionCategory name: HR_OPERATIONAL | ACCESS_CONTROL
    private String actionGroup;    // AuditActionGroup name: EMPLOYEE | ATTENDANCE | LEAVE | EXPENSE | ASSET | ACCESS | OTHER
    private UUID targetId;
    private String targetLabel;    // best-effort human label, never null (has a fallback)
    private String beforeState;    // raw JSON string, pass-through, nullable
    private String afterState;     // raw JSON string, pass-through, nullable
    private LocalDateTime occurredAt;
}
