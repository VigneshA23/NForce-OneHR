package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AdHocPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AdHocPaymentRepository extends JpaRepository<AdHocPayment, UUID> {

    @Query("SELECT p FROM AdHocPayment p WHERE p.payrollRun.id = :runId ORDER BY p.addedAt")
    List<AdHocPayment> findByPayrollRunId(@Param("runId") UUID runId);

    @Query("SELECT p FROM AdHocPayment p WHERE p.payrollRun.id = :runId AND p.employeeId = :employeeId ORDER BY p.addedAt")
    List<AdHocPayment> findByPayrollRunIdAndEmployeeId(@Param("runId") UUID runId, @Param("employeeId") UUID employeeId);
}
