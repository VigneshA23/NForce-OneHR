package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AdHocDeductionInstallment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AdHocDeductionInstallmentRepository extends JpaRepository<AdHocDeductionInstallment, UUID> {

    @Query("SELECT i FROM AdHocDeductionInstallment i WHERE i.adHocDeduction.id = :deductionId AND i.payrollRun.id = :runId")
    Optional<AdHocDeductionInstallment> findByAdHocDeductionIdAndPayrollRunId(
            @Param("deductionId") UUID deductionId, @Param("runId") UUID runId);

    @Query("SELECT i FROM AdHocDeductionInstallment i WHERE i.payrollRun.id = :runId")
    List<AdHocDeductionInstallment> findByPayrollRunId(@Param("runId") UUID runId);

    @Query("SELECT i FROM AdHocDeductionInstallment i WHERE i.adHocDeduction.id = :deductionId ORDER BY i.appliedAt")
    List<AdHocDeductionInstallment> findByAdHocDeductionId(@Param("deductionId") UUID deductionId);
}
