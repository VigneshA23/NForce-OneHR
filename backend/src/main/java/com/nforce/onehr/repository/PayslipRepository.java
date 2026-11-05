package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Payslip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PayslipRepository extends JpaRepository<Payslip, UUID> {

    @Query("SELECT p FROM Payslip p WHERE p.payrollRun.id = :runId ORDER BY p.employeeId")
    List<Payslip> findByPayrollRunId(@Param("runId") UUID runId);

    Optional<Payslip> findByPayrollRunIdAndEmployeeId(UUID payrollRunId, UUID employeeId);

    // Explicit bulk @Modifying delete, not a derived deleteBy... method — a derived delete loads
    // entities and defers their removal to Hibernate's next flush, and Hibernate's action queue
    // always runs pending INSERTs before pending DELETEs in the same flush. That ordering made
    // PayrollRunService's regenerate-while-DRAFT path (delete old payslips, then insert new ones
    // for the same employee) hit uq_payslips_run_employee on the still-undeleted old row — caught
    // live, not hypothetical. This bulk query executes immediately instead.
    @Modifying
    @Query("DELETE FROM Payslip p WHERE p.payrollRun.id = :runId")
    void deleteByPayrollRunId(@Param("runId") UUID runId);
}
