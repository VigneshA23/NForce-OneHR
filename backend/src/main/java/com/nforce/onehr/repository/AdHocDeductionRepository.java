package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AdHocDeduction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AdHocDeductionRepository extends JpaRepository<AdHocDeduction, UUID> {

    List<AdHocDeduction> findByEmployeeIdOrderByCreatedAtDesc(UUID employeeId);

    List<AdHocDeduction> findByEmployeeIdAndStatus(UUID employeeId, String status);
}
