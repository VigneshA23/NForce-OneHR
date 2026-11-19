package com.nforce.onehr.repository;

import com.nforce.onehr.entity.ProfileChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProfileChangeRequestRepository extends JpaRepository<ProfileChangeRequest, UUID> {

    Optional<ProfileChangeRequest> findByEmployeeUserIdAndStatus(UUID employeeUserId, String status);

    boolean existsByEmployeeUserIdAndStatus(UUID employeeUserId, String status);

    List<ProfileChangeRequest> findByEmployeeUserIdOrderByCreatedAtDesc(UUID employeeUserId);

    List<ProfileChangeRequest> findByStatusOrderByCreatedAtAsc(String status);
}
