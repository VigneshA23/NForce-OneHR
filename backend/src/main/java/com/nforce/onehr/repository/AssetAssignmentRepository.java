package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AssetAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssetAssignmentRepository extends JpaRepository<AssetAssignment, Long> {

    List<AssetAssignment> findByEmployeeUserIdAndEffectiveToIsNull(UUID employeeUserId);

    Optional<AssetAssignment> findByAssetIdAndEffectiveToIsNull(Long assetId);

    List<AssetAssignment> findByEmployeeUserIdInAndEffectiveToIsNull(List<UUID> employeeUserIds);

    long countByEffectiveToIsNull();

    @Query("SELECT COUNT(DISTINCT a.employeeUserId) FROM AssetAssignment a " +
           "WHERE a.employeeUserId IN :employeeIds AND a.effectiveTo IS NULL")
    long countDistinctEmployeesWithAssignments(List<UUID> employeeIds);

    // Overdue: assigned to inactive/deleted employees — used for "Overdue Returns" tile
    @Query("SELECT a FROM AssetAssignment a " +
           "JOIN User u ON u.id = a.employeeUserId " +
           "WHERE a.effectiveTo IS NULL AND (u.active = false OR u.deletedAt IS NOT NULL)")
    List<AssetAssignment> findOverdueAssignments();

    @Query("SELECT a FROM AssetAssignment a " +
           "JOIN User u ON u.id = a.employeeUserId " +
           "WHERE a.employeeUserId IN :employeeIds " +
           "AND a.effectiveTo IS NULL " +
           "AND (u.active = false OR u.deletedAt IS NOT NULL)")
    List<AssetAssignment> findOverdueAssignmentsForEmployees(List<UUID> employeeIds);
}
