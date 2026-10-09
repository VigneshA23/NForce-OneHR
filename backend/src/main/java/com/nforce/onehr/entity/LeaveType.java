package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "leave_types")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeaveType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, length = 50)
    private String name;

    // PAID or UNPAID (see LeaveTypeClassification) — drives whether an approved request against
    // this type deducts from the employee's LeaveBalance (see LeaveService#approve/#submitRequest).
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String classification = LeaveTypeClassification.PAID;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public boolean isPaid() {
        return LeaveTypeClassification.PAID.equals(classification);
    }
}
