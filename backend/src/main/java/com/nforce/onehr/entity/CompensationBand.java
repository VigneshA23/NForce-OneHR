package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/** US-A13: an average-CTC band. Never linked to an individual's actual salary. */
@Entity
@Table(name = "compensation_bands")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CompensationBand {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "band_label", nullable = false, length = 60)
    private String bandLabel;

    @Column(name = "avg_ctc", nullable = false, precision = 14, scale = 2)
    private BigDecimal avgCtc;
}
