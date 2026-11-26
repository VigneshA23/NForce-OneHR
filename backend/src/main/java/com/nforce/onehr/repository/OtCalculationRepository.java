package com.nforce.onehr.repository;

import com.nforce.onehr.entity.OtCalculation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OtCalculationRepository extends JpaRepository<OtCalculation, UUID> {
    Optional<OtCalculation> findByEmployeeUserIdAndWorkDate(UUID employeeUserId, LocalDate workDate);

    List<OtCalculation> findByWorkDateBetweenOrderByWorkDateDescEmployeeUserIdAsc(LocalDate from, LocalDate to);

    List<OtCalculation> findByEmployeeUserIdInAndWorkDateBetween(Collection<UUID> ids, LocalDate from, LocalDate to);

    List<OtCalculation> findByPayrollRunId(UUID runId);

    /** Approved OT inside a cycle that is free or already claimed by this run (regeneration-safe). */
    @Query("SELECT c FROM OtCalculation c WHERE c.employeeUserId = :employeeId AND c.status = 'APPROVED' "
            + "AND c.workDate BETWEEN :from AND :to AND (c.payrollRunId IS NULL OR c.payrollRunId = :runId)")
    List<OtCalculation> findPayable(@Param("employeeId") UUID employeeId, @Param("from") LocalDate from,
                                    @Param("to") LocalDate to, @Param("runId") UUID runId);

    @Query("SELECT c FROM OtCalculation c WHERE c.status = 'APPROVED' AND c.workDate BETWEEN :from AND :to")
    List<OtCalculation> findApprovedInRange(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
