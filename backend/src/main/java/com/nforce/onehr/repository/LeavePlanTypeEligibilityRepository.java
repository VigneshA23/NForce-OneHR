package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeavePlanTypeEligibility;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LeavePlanTypeEligibilityRepository extends JpaRepository<LeavePlanTypeEligibility, UUID> {

    List<LeavePlanTypeEligibility> findByRuleIdIn(Collection<UUID> ruleIds);
}
