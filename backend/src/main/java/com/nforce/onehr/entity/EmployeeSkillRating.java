package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** US-A07: an employee's 1-5 self-rating of a taxonomy skill, with an optional manager override. */
@Entity
@Table(name = "employee_skill_ratings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmployeeSkillRating {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "self_rating", nullable = false)
    private int selfRating;

    @Column(name = "manager_rating")
    private Integer managerRating;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
