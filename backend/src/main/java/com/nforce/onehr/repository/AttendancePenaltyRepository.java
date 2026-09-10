package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AttendancePenalty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface AttendancePenaltyRepository extends JpaRepository<AttendancePenalty, UUID>, JpaSpecificationExecutor<AttendancePenalty> {

    // Backs AttendancePenaltyEvaluationService's duplicate-evaluation guard — re-running
    // detection (e.g. a repeated dashboard load) must never create a second penalty row for the
    // same employee/date/discrepancy, regardless of the existing row's status.
    boolean existsByEmployeeUserIdAndIncidentDateAndDiscrepancyType(
            UUID employeeUserId, LocalDate incidentDate, String discrepancyType);

    // Every penalty (any discrepancy type) recorded for this employee on this date — a
    // regularization corrects the whole day's attendance record, not one discrepancy type, so
    // approving it must be able to reverse every still-active penalty that date produced. See
    // AttendancePenaltyService#reverseForApprovedRegularization.
    List<AttendancePenalty> findByEmployeeUserIdAndIncidentDate(UUID employeeUserId, LocalDate incidentDate);

    // Backs PenalisationPolicyManagementService#delete's historical-integrity guard — AttendancePenalty
    // deliberately has no FK on policyId (a deleted policy must never orphan a historical penalty's
    // own record of what happened), so this existence check is the only way to detect the reference
    // before allowing the policy's version history to be hard-deleted.
    boolean existsByPolicyId(UUID policyId);

    /**
     * Atomic, race-safe status transition for {@link com.nforce.onehr.service.AttendancePenaltyService#finalizeReversal} —
     * the WHERE clause re-checks the allowed-status precondition in the same statement as the write,
     * so two concurrent cancel/reverse calls on the same penalty can never both succeed: whichever
     * commits second finds zero matching rows (return value 0) instead of blindly overwriting the
     * first call's already-applied reversal and re-running leave-balance restoration a second time.
     */
    @Modifying
    @Query("UPDATE AttendancePenalty p SET p.status = :newStatus, p.cancelledBy = :actorId, "
            + "p.cancelledAt = :now, p.cancellationReason = :reason "
            + "WHERE p.id = :id AND p.status IN :allowedCurrentStatuses")
    int transitionStatus(@Param("id") UUID id, @Param("newStatus") String newStatus, @Param("actorId") UUID actorId,
                          @Param("now") LocalDateTime now, @Param("reason") String reason,
                          @Param("allowedCurrentStatuses") Collection<String> allowedCurrentStatuses);
}
