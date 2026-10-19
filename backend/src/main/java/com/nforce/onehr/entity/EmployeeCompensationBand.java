package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/** US-A13: which band an employee falls into (a band label only, no figure about the person). */
@Entity
@Table(name = "employee_compensation_bands")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmployeeCompensationBand {

    @Id
    @Column(name = "employee_user_id")
    private UUID employeeUserId;

    @Column(name = "band_id", nullable = false)
    private UUID bandId;
}
