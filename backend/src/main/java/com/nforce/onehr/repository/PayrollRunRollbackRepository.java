package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PayrollRunRollback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PayrollRunRollbackRepository extends JpaRepository<PayrollRunRollback, UUID> {

    List<PayrollRunRollback> findByPayrollRunIdOrderByRolledBackAtDesc(UUID payrollRunId);
}
