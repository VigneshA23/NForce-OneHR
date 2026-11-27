package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeavePlanVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LeavePlanVersionRepository extends JpaRepository<LeavePlanVersion, UUID> {

    List<LeavePlanVersion> findByPlanIdOrderByEffectiveFromAsc(UUID planId);
}
