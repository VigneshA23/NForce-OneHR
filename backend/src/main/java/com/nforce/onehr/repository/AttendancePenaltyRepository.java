package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AttendancePenalty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.UUID;

@Repository
public interface AttendancePenaltyRepository extends JpaRepository<AttendancePenalty, UUID>, JpaSpecificationExecutor<AttendancePenalty> {

    // Backs AttendancePenaltyEvaluationService's duplicate-evaluation guard — re-running
    // detection (e.g. a repeated dashboard load) must never create a second penalty row for the
    // same employee/date/discrepancy, regardless of the existing row's status.
    boolean existsByEmployeeUserIdAndIncidentDateAndDiscrepancyType(
            UUID employeeUserId, LocalDate incidentDate, String discrepancyType);
}
