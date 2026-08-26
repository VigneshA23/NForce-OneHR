package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One HR support request raised by an employee. {@code employeeUserId} is always resolved
 * server-side from the JWT-authenticated caller — never accepted from a request payload.
 * {@code status}/{@code priority} stay plain String columns (this codebase's established
 * convention for status fields — see LeaveRequest, OnboardingChecklist) rather than a real
 * JPA enum, so the allowed values live in HelpdeskService's transition guard, not in DDL.
 */
@Entity
@Table(name = "helpdesk_tickets")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HelpdeskTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ticket_number", nullable = false, unique = true, length = 20)
    private String ticketNumber;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private HelpdeskCategory category;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    // OPEN | IN_PROGRESS | RESOLVED | CLOSED — see TicketStatus, enforced by chk_helpdesk_status (V93)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private String status = "OPEN";

    // LOW | MEDIUM | HIGH | URGENT
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String priority = "MEDIUM";

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "resolved_at", columnDefinition = "TIMESTAMPTZ")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }
}
