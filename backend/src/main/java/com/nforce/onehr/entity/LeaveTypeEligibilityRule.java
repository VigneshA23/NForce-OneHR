package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * One "applies to" value of a leave type (V314), e.g. GENDER = Female. Values of the same rule type
 * are OR'ed; different rule types are AND'ed. A type with no rules applies to everyone.
 */
@Entity
@Table(name = "leave_type_eligibility_rules")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeaveTypeEligibilityRule {

    public static final String GENDER = "GENDER";
    public static final String DEPARTMENT = "DEPARTMENT";
    public static final String LOCATION = "LOCATION";
    public static final String EMPLOYMENT_TYPE = "EMPLOYMENT_TYPE";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "leave_type_id", nullable = false)
    private UUID leaveTypeId;

    @Column(name = "rule_type", nullable = false, length = 20)
    private String ruleType;

    /** Gender text, department or location id, or employment type. */
    @Column(nullable = false, length = 100)
    private String value;
}
