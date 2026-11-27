package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeavePlanTypeClubbing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LeavePlanTypeClubbingRepository extends JpaRepository<LeavePlanTypeClubbing, UUID> {

    List<LeavePlanTypeClubbing> findByRuleIdIn(Collection<UUID> ruleIds);
}
