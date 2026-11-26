package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PenaltyEarlyWarning;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.UUID;

@Repository
public interface PenaltyEarlyWarningRepository extends JpaRepository<PenaltyEarlyWarning, UUID> {

    boolean existsByEmployeeUserIdAndWarningTypeAndPeriodStartAndThresholdKey(
            UUID employeeUserId, String warningType, LocalDate periodStart, String thresholdKey);
}
