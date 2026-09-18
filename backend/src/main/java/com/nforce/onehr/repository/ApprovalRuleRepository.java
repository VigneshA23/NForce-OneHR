package com.nforce.onehr.repository;

import com.nforce.onehr.entity.ApprovalRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApprovalRuleRepository extends JpaRepository<ApprovalRule, UUID> {

    List<ApprovalRule> findByRequestTypeOrderByCreatedAtDesc(String requestType);

    // The one production read path ApprovalRuleEvaluationService consumes — see V184's partial
    // unique index guaranteeing there is never more than one of these per requestType.
    Optional<ApprovalRule> findByRequestTypeAndActiveTrue(String requestType);

    boolean existsByRequestTypeAndConditionFieldAndOperatorAndConditionValueAndIdNot(
            String requestType, String conditionField, String operator, String conditionValue, UUID excludedId);
}
