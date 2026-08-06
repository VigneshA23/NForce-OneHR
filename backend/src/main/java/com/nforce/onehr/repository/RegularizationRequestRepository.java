package com.nforce.onehr.repository;

import com.nforce.onehr.entity.RegularizationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface RegularizationRequestRepository extends JpaRepository<RegularizationRequest, UUID> {

    List<RegularizationRequest> findByEmployeeUserIdOrderByCreatedAtDesc(UUID employeeUserId);

    // Backs audit-log target search — resolves which regularization requests belong to a set of employees.
    @Query("SELECT r.id FROM RegularizationRequest r WHERE r.employeeUserId IN :employeeUserIds")
    Set<UUID> findIdsByEmployeeUserIdIn(Collection<UUID> employeeUserIds);

    List<RegularizationRequest> findByStatus(String status);

    boolean existsByEmployeeUserIdAndAttendanceDateAndStatus(UUID employeeUserId, LocalDate attendanceDate, String status);
}
