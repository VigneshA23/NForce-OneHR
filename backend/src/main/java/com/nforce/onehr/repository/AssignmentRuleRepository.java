package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AssignmentRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AssignmentRuleRepository extends JpaRepository<AssignmentRule, UUID> {

    List<AssignmentRule> findByDomainOrderByPriorityAscCreatedAtAsc(String domain);

    List<AssignmentRule> findByDomainAndActiveTrueOrderByPriorityAscCreatedAtAsc(String domain);

    boolean existsByDomainAndTargetId(String domain, UUID targetId);
}
