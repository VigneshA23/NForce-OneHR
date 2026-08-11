package com.nforce.onehr.repository;

import com.nforce.onehr.entity.WebClockInRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface WebClockInRequestRepository extends JpaRepository<WebClockInRequest, UUID> {

    List<WebClockInRequest> findByEmployeeUserIdOrderByCreatedAtDesc(UUID employeeUserId);

    // Backs audit-log target search — resolves which web clock-in requests belong to a set of employees.
    @Query("SELECT r.id FROM WebClockInRequest r WHERE r.employeeUserId IN :employeeUserIds")
    Set<UUID> findIdsByEmployeeUserIdIn(Collection<UUID> employeeUserIds);

    List<WebClockInRequest> findByStatus(String status);

    Optional<WebClockInRequest> findByEmployeeUserIdAndWorkDateAndStatus(
            UUID employeeUserId, LocalDate workDate, String status);

    boolean existsByEmployeeUserIdAndWorkDateAndStatus(UUID employeeUserId, LocalDate workDate, String status);

    // Backs the "Remote Clock-ins" / "Remote Clock-in Requests Summary" / "Web Clock-ins" report
    // cards (ONEHR-109) — one entity backs all three, a manager's team over a date range.
    List<WebClockInRequest> findByEmployeeUserIdInAndWorkDateBetween(
            Collection<UUID> employeeUserIds, LocalDate from, LocalDate to);
}
