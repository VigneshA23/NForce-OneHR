package com.nforce.onehr.repository;

import com.nforce.onehr.entity.OtCalculationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OtCalculationHistoryRepository extends JpaRepository<OtCalculationHistory, UUID> {
    List<OtCalculationHistory> findByCalculationIdOrderByCreatedAtDesc(UUID calculationId);
}
