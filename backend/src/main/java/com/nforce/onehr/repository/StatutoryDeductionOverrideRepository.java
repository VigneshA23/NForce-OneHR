package com.nforce.onehr.repository;

import com.nforce.onehr.entity.StatutoryDeductionOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StatutoryDeductionOverrideRepository extends JpaRepository<StatutoryDeductionOverride, UUID> {

    Optional<StatutoryDeductionOverride> findByEmployeeIdAndRuleTypeAndCycleStartAndCycleEnd(
            UUID employeeId, String ruleType, LocalDate cycleStart, LocalDate cycleEnd);

    List<StatutoryDeductionOverride> findByEmployeeId(UUID employeeId);
}
