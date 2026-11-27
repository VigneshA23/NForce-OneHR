package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/** LV-02: a leave type that may not be taken adjacent to the rule's own type (only non-working days between). */
@Entity
@Table(name = "leave_plan_type_clubbing")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeavePlanTypeClubbing {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "blocked_leave_type_id", nullable = false)
    private UUID blockedLeaveTypeId;
}
