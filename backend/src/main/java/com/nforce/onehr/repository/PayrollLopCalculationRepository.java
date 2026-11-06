package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PayrollLopCalculation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PayrollLopCalculationRepository extends JpaRepository<PayrollLopCalculation, UUID> {

    Optional<PayrollLopCalculation> findByEmployeeIdAndCycleStartAndCycleEnd(
            UUID employeeId, LocalDate cycleStart, LocalDate cycleEnd);
}
