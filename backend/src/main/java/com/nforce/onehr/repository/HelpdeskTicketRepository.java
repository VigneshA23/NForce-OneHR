package com.nforce.onehr.repository;

import com.nforce.onehr.entity.HelpdeskTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Extends {@link JpaSpecificationExecutor} — same precedent as AuditLogRepository — because
 * the ticket queue/mine endpoints have several independently-optional filters (status, search,
 * assignee) that must be combined at the DB level under real pagination, unlike Leave/
 * Onboarding's simpler in-memory list filtering.
 */
@Repository
public interface HelpdeskTicketRepository extends JpaRepository<HelpdeskTicket, UUID>, JpaSpecificationExecutor<HelpdeskTicket> {

    Optional<HelpdeskTicket> findByIdAndEmployeeUserId(UUID id, UUID employeeUserId);

    @Query(value = "SELECT nextval('helpdesk_ticket_no_seq')", nativeQuery = true)
    long nextTicketSequence();

    // ── Analytics (HelpdeskAnalyticsService) ────────────────

    /** [categoryName, ticketCount], every ticket ever raised, most-asked-about first. */
    @Query("SELECT t.category.name, COUNT(t) FROM HelpdeskTicket t GROUP BY t.category.name ORDER BY COUNT(t) DESC")
    List<Object[]> countByCategory();

    /** Average time-to-first-resolution across every ticket that has ever reached RESOLVED, in seconds — null if none have. */
    @Query(value = "SELECT AVG(EXTRACT(EPOCH FROM (resolved_at - created_at))) FROM helpdesk_tickets WHERE resolved_at IS NOT NULL",
            nativeQuery = true)
    Double averageResolutionSeconds();

    /** [assignedTo, activeTicketCount] — currently OPEN or IN_PROGRESS tickets, per assignee. */
    @Query("SELECT t.assignedTo, COUNT(t) FROM HelpdeskTicket t WHERE t.assignedTo IS NOT NULL AND t.status IN ('OPEN', 'IN_PROGRESS') GROUP BY t.assignedTo")
    List<Object[]> activeWorkloadByAgent();

    /** [resolvedBy, avgResolutionSeconds] — per agent, across every ticket they've ever resolved. */
    @Query(value = "SELECT resolved_by, AVG(EXTRACT(EPOCH FROM (resolved_at - created_at))) FROM helpdesk_tickets "
            + "WHERE resolved_by IS NOT NULL AND resolved_at IS NOT NULL GROUP BY resolved_by", nativeQuery = true)
    List<Object[]> averageResolutionSecondsByAgent();

    long countBySatisfactionRatingIsNotNull();

    long countBySatisfactionRatingGreaterThanEqual(int threshold);

    @Query("SELECT AVG(t.satisfactionRating) FROM HelpdeskTicket t WHERE t.satisfactionRating IS NOT NULL")
    Double averageSatisfactionRating();
}
