package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PolicyAcknowledgment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PolicyAcknowledgmentRepository extends JpaRepository<PolicyAcknowledgment, Long> {

    List<PolicyAcknowledgment> findByEmployeeUserIdOrderByPolicy_PublishedAtDesc(UUID employeeUserId);

    // Backs PolicyService#listAcknowledgments — the HR-wide "who has/hasn't acknowledged this
    // policy" view. Joins User to exclude soft-deleted employees' (historical) acknowledgment
    // rows; PolicyService#publish already only seeds new rows for active employees, so this only
    // ever hides rows created before the employee was deleted.
    @Query("SELECT a FROM PolicyAcknowledgment a JOIN User u ON u.id = a.employeeUserId "
         + "WHERE a.policy.id = :policyId AND u.deletedAt IS NULL ORDER BY a.acknowledgedAt DESC")
    List<PolicyAcknowledgment> findByPolicyIdOrderByAcknowledgedAtDesc(@Param("policyId") Long policyId);

    Optional<PolicyAcknowledgment> findByPolicyIdAndEmployeeUserId(Long policyId, UUID employeeUserId);

    List<PolicyAcknowledgment> findByPolicyIdAndAcknowledgedAtIsNull(Long policyId);

    @Query("SELECT COUNT(a) FROM PolicyAcknowledgment a WHERE a.employeeUserId = :userId AND a.acknowledgedAt IS NULL AND a.policy.active = true AND a.policy.required = true")
    long countPendingRequiredForEmployee(@Param("userId") UUID userId);

    @Query("SELECT COUNT(a) FROM PolicyAcknowledgment a WHERE a.acknowledgedAt IS NULL AND a.policy.active = true AND a.policy.required = true")
    long countAllPendingRequired();

    @Modifying
    @Query("DELETE FROM PolicyAcknowledgment a WHERE a.policy.id = :policyId")
    void deleteByPolicyId(@Param("policyId") Long policyId);
}
