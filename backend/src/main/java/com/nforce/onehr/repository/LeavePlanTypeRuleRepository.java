package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeavePlanTypeRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LeavePlanTypeRuleRepository extends JpaRepository<LeavePlanTypeRule, UUID> {

    List<LeavePlanTypeRule> findByVersionId(UUID versionId);

    /** Names of the plans whose versions include {@code leaveTypeId}, for the leave-type catalogue. */
    @Query("SELECT DISTINCT p.name FROM LeavePlanTypeRule r, LeavePlanVersion v, LeavePlan p "
         + "WHERE r.versionId = v.id AND v.planId = p.id AND r.leaveTypeId = :typeId ORDER BY p.name")
    List<String> findPlanNamesUsingType(@Param("typeId") UUID leaveTypeId);
}
