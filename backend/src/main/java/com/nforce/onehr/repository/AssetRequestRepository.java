package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AssetRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AssetRequestRepository extends JpaRepository<AssetRequest, Long> {

    List<AssetRequest> findByEmployeeUserIdOrderByCreatedAtDesc(UUID employeeUserId);

    // Both back AssetService#listPendingForApprover (admin branch is company-wide; the manager
    // branch further narrows by current-direct-report ids, which is itself already deletedAt-
    // aware) and the HR "pending fulfillment" tile count — joins User to exclude soft-deleted
    // requesters' asset requests everywhere they're used.
    @Query("SELECT r FROM AssetRequest r JOIN User u ON u.id = r.employeeUserId "
         + "WHERE r.status = :status AND u.deletedAt IS NULL")
    List<AssetRequest> findByStatus(@Param("status") String status);

    @Query("SELECT r FROM AssetRequest r JOIN User u ON u.id = r.employeeUserId "
         + "WHERE r.status IN :statuses AND u.deletedAt IS NULL")
    List<AssetRequest> findByStatusIn(@Param("statuses") List<String> statuses);

    List<AssetRequest> findByStatusAndEmployeeUserIdIn(String status, List<UUID> employeeUserIds);

    List<AssetRequest> findByStatusInAndEmployeeUserIdIn(List<String> statuses, List<UUID> employeeUserIds);

    long countByEmployeeUserIdAndStatus(UUID employeeUserId, String status);
}
