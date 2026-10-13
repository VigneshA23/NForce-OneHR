package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "designations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Designation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, columnDefinition = "citext")
    private String title;

    @Column(length = 50)
    private String grade;

    @Column(length = 20)
    private String level;

    // Nullable only for rows that predate V202 and haven't been re-saved yet — every designation
    // created/updated through OrgService now requires one (see CreateDesignationRequest).
    @Column(name = "department_id")
    private UUID departmentId;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
