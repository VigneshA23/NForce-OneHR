package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AttendancePenaltyDispute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttendancePenaltyDisputeRepository extends JpaRepository<AttendancePenaltyDispute, UUID> {

    Optional<AttendancePenaltyDispute> findByPenaltyId(UUID penaltyId);

    List<AttendancePenaltyDispute> findByPenaltyIdIn(Collection<UUID> penaltyIds);

    List<AttendancePenaltyDispute> findByStatusOrderByCreatedAtAsc(String status);

    List<AttendancePenaltyDispute> findByStatusAndEmployeeUserIdInOrderByCreatedAtAsc(String status, Collection<UUID> employeeUserIds);
}
