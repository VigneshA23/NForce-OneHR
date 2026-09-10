package com.nforce.onehr.repository;

import com.nforce.onehr.entity.OvertimeRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OvertimeRequestRepository extends JpaRepository<OvertimeRequest, UUID> {

    List<OvertimeRequest> findByEmployeeUserIdOrderByCreatedAtDesc(UUID employeeUserId);

    // Backs OvertimeRequestService#listPendingForApprover's company-wide (not manager-scoped)
    // HR/Super Admin branch — joins User to exclude soft-deleted requesters' overtime requests.
    @Query("SELECT o FROM OvertimeRequest o JOIN User u ON u.id = o.employeeUserId "
         + "WHERE o.status = :status AND u.deletedAt IS NULL")
    List<OvertimeRequest> findByStatus(@Param("status") String status);
}
