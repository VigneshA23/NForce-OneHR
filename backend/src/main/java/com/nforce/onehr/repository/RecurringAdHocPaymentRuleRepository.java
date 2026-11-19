package com.nforce.onehr.repository;

import com.nforce.onehr.entity.RecurringAdHocPaymentRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RecurringAdHocPaymentRuleRepository extends JpaRepository<RecurringAdHocPaymentRule, UUID> {

    @Query("SELECT r FROM RecurringAdHocPaymentRule r ORDER BY r.createdAt DESC")
    List<RecurringAdHocPaymentRule> findAllOrdered();

    // PayrollRunService#generateDraft's auto-apply — every ACTIVE rule that applies to this pay
    // group, either scoped to it directly or scoped to every pay group (payGroupId IS NULL).
    @Query("SELECT r FROM RecurringAdHocPaymentRule r WHERE r.active = true AND (r.payGroupId IS NULL OR r.payGroupId = :payGroupId)")
    List<RecurringAdHocPaymentRule> findActiveForPayGroup(@Param("payGroupId") UUID payGroupId);
}
