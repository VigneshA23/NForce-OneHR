package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AssetRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AssetRequestRepository extends JpaRepository<AssetRequest, Long> {

    List<AssetRequest> findByEmployeeUserIdOrderByCreatedAtDesc(UUID employeeUserId);

    List<AssetRequest> findByStatus(String status);

    List<AssetRequest> findByStatusIn(List<String> statuses);

    List<AssetRequest> findByStatusAndEmployeeUserIdIn(String status, List<UUID> employeeUserIds);

    List<AssetRequest> findByStatusInAndEmployeeUserIdIn(List<String> statuses, List<UUID> employeeUserIds);

    long countByEmployeeUserIdAndStatus(UUID employeeUserId, String status);
}
