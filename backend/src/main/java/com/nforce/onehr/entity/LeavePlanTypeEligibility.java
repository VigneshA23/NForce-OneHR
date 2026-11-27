package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/** LV-01: one "applies to" value of a plan's leave type (was {@code leave_type_eligibility_rules}, V314). */
@Entity
@Table(name = "leave_plan_type_eligibility")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeavePlanTypeEligibility {

    public static final String GENDER = "GENDER";
    public static final String DEPARTMENT = "DEPARTMENT";
    public static final String LOCATION = "LOCATION";
    public static final String EMPLOYMENT_TYPE = "EMPLOYMENT_TYPE";
    /** LV-06 */
    public static final String MARITAL_STATUS = "MARITAL_STATUS";
    /** LV-06: the grade of the employee's designation. */
    public static final String GRADE = "GRADE";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "rule_type", nullable = false, length = 20)
    private String ruleType;

    @Column(nullable = false, length = 100)
    private String value;
}
