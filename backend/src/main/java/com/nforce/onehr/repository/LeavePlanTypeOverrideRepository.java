package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeavePlanTypeOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LeavePlanTypeOverrideRepository extends JpaRepository<LeavePlanTypeOverride, UUID> {

    List<LeavePlanTypeOverride> findByRuleIdIn(Collection<UUID> ruleIds);
}
