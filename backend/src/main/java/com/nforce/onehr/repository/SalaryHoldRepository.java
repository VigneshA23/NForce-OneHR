package com.nforce.onehr.repository;

import com.nforce.onehr.entity.SalaryHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface SalaryHoldRepository extends JpaRepository<SalaryHold, UUID> {

    @Query("SELECT h FROM SalaryHold h WHERE h.payrollRun.id = :runId ORDER BY h.createdAt")
    List<SalaryHold> findByPayrollRunId(@Param("runId") UUID runId);

    // Type-scoped: generateDraft calls this with PROCESSING (only that type excludes from draft
    // generation), disburse calls it with PAYOUT (only that type withholds a payment).
    @Query("SELECT h.employeeId FROM SalaryHold h WHERE h.payrollRun.id = :runId AND h.status = 'ACTIVE' AND h.holdType = :holdType")
    Set<UUID> findActiveEmployeeIdsByPayrollRunIdAndType(@Param("runId") UUID runId, @Param("holdType") String holdType);

    boolean existsByPayrollRunIdAndEmployeeIdAndStatus(UUID payrollRunId, UUID employeeId, String status);
}
