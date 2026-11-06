package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PayrollProration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PayrollProrationRepository extends JpaRepository<PayrollProration, UUID> {

    Optional<PayrollProration> findByEmployeeIdAndCycleStartAndCycleEnd(
            UUID employeeId, LocalDate cycleStart, LocalDate cycleEnd);
}
