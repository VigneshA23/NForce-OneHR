package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/** US-A07: the HR-managed skill list every rating, search and opportunity points at. */
@Entity
@Table(name = "skill_taxonomy")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SkillTaxonomy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String skill;

    @Column(nullable = false, length = 100)
    private String category;
}
