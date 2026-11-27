package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PayrollLopCalculation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PayrollLopCalculationRepository extends JpaRepository<PayrollLopCalculation, UUID> {

    Optional<PayrollLopCalculation> findByEmployeeIdAndCycleStartAndCycleEnd(
            UUID employeeId, LocalDate cycleStart, LocalDate cycleEnd);

    /** LV-03 accrual pause: the employee's LOP calculations of cycles ending in [from, to] ({@code employeeId} = user id). */
    List<PayrollLopCalculation> findByEmployeeIdAndCycleEndBetween(UUID employeeId, LocalDate from, LocalDate to);
}
