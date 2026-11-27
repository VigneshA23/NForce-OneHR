package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AssignmentRuleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssignmentRuleAssignmentRepository extends JpaRepository<AssignmentRuleAssignment, UUID> {

    /** The still-open row (effective_to is null) for an employee in a domain, if any. */
    Optional<AssignmentRuleAssignment> findFirstByDomainAndEmployeeUserIdAndEffectiveToIsNullOrderByEffectiveFromDesc(
            String domain, UUID employeeUserId);

    /** The row effective on {@code date}: started on/before it and not yet closed before it. */
    @Query("SELECT a FROM AssignmentRuleAssignment a WHERE a.domain = :domain AND a.employeeUserId = :employeeUserId "
         + "AND a.effectiveFrom <= :date AND (a.effectiveTo IS NULL OR a.effectiveTo >= :date) "
         + "ORDER BY a.effectiveFrom DESC, a.createdAt DESC")
    List<AssignmentRuleAssignment> findEffectiveAt(@Param("domain") String domain,
                                                   @Param("employeeUserId") UUID employeeUserId,
                                                   @Param("date") LocalDate date);

    /** Every employee's row effective on {@code date} - one query for bulk resolution. */
    @Query("SELECT a FROM AssignmentRuleAssignment a WHERE a.domain = :domain "
         + "AND a.effectiveFrom <= :date AND (a.effectiveTo IS NULL OR a.effectiveTo >= :date)")
    List<AssignmentRuleAssignment> findAllEffectiveAt(@Param("domain") String domain, @Param("date") LocalDate date);

    long countByRuleIdAndEffectiveToIsNull(UUID ruleId);

    List<AssignmentRuleAssignment> findByDomainAndEmployeeUserIdOrderByEffectiveFromDescCreatedAtDesc(String domain, UUID employeeUserId);

    List<AssignmentRuleAssignment> findByDomainAndEmployeeUserIdInAndEffectiveToIsNull(String domain, java.util.Collection<UUID> employeeUserIds);

    /** Every row (history included) of some employees in a domain - leave plan resolution for any date (LV-01). */
    List<AssignmentRuleAssignment> findByDomainAndEmployeeUserIdIn(String domain, java.util.Collection<UUID> employeeUserIds);

    List<AssignmentRuleAssignment> findByDomain(String domain);

    boolean existsByDomainAndTargetId(String domain, UUID targetId);
}
