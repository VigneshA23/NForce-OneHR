package com.nforce.onehr.repository;

import com.nforce.onehr.entity.ExpenseClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface ExpenseClaimRepository extends JpaRepository<ExpenseClaim, UUID> {

    List<ExpenseClaim> findByEmployeeUserIdOrderByCreatedAtDesc(UUID employeeUserId);

    // Backs audit-log target search — resolves which expense claims belong to a set of employees.
    @Query("SELECT c.id FROM ExpenseClaim c WHERE c.employeeUserId IN :employeeUserIds")
    Set<UUID> findIdsByEmployeeUserIdIn(Collection<UUID> employeeUserIds);

    List<ExpenseClaim> findByEmployeeUserIdInAndStatus(List<UUID> employeeUserIds, String status);

    List<ExpenseClaim> findByEmployeeUserIdInOrderByCreatedAtDesc(List<UUID> employeeUserIds);

    // Both of the below back company-wide (not manager-scoped) HR/Finance queues — final-approver
    // pending, cleared-for-payroll, and the payroll-pending count — so, unlike the employeeUserId-
    // scoped queries above (which are reached only through an already-filtered report list),
    // these need their own User join to exclude soft-deleted requesters' claims.
    @Query("SELECT c FROM ExpenseClaim c JOIN User u ON u.id = c.employeeUserId "
         + "WHERE c.status = :status AND u.deletedAt IS NULL")
    List<ExpenseClaim> findByStatus(@Param("status") String status);

    @Query("SELECT c FROM ExpenseClaim c JOIN User u ON u.id = c.employeeUserId "
         + "WHERE c.status IN :statuses AND u.deletedAt IS NULL")
    List<ExpenseClaim> findByStatusIn(@Param("statuses") Collection<String> statuses);

    long countByEmployeeUserIdInAndStatus(List<UUID> employeeUserIds, String status);

    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM ExpenseClaim c " +
           "WHERE c.employeeUserId IN :employeeIds AND c.status = :status")
    BigDecimal sumAmountByEmployeeUserIdInAndStatus(List<UUID> employeeIds, String status);

    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM ExpenseClaim c WHERE c.status = :status")
    BigDecimal sumAmountByStatus(String status);

    // Claims the current user approved at manager stage within a month window
    @Query("SELECT c FROM ExpenseClaim c WHERE c.managerDecidedBy = :managerId " +
           "AND c.managerDecidedAt >= :from AND c.managerDecidedAt < :to")
    List<ExpenseClaim> findManagerApprovedInWindow(UUID managerId, Instant from, Instant to);

    // Employee "approved this month" tile: CLEARED_FOR_PAYROLL or PAID, final_decided_at in current month
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM ExpenseClaim c " +
           "WHERE c.employeeUserId = :userId " +
           "AND c.status IN ('CLEARED_FOR_PAYROLL', 'PAID') " +
           "AND c.finalDecidedAt >= :from AND c.finalDecidedAt < :to")
    BigDecimal sumApprovedThisMonth(UUID userId, Instant from, Instant to);

    @Query("SELECT COUNT(c) FROM ExpenseClaim c " +
           "WHERE c.employeeUserId = :userId " +
           "AND c.status IN ('CLEARED_FOR_PAYROLL', 'PAID') " +
           "AND c.finalDecidedAt >= :from AND c.finalDecidedAt < :to")
    long countApprovedThisMonth(UUID userId, Instant from, Instant to);
}
