package com.nforce.onehr.repository;

import com.nforce.onehr.entity.OtAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OtAdjustmentRepository extends JpaRepository<OtAdjustment, UUID> {
    List<OtAdjustment> findByCalculationIdOrderByRequestedAtDesc(UUID calculationId);
    boolean existsByCalculationIdAndStatus(UUID calculationId, String status);
    List<OtAdjustment> findAllByOrderByRequestedAtDesc();
}
