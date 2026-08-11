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

    // The employee's approved-but-not-yet-checked-out request, if any — independent of
    // calendar date. A web clock-in approved before midnight (shift crosses into the next
    // day) is still filed under *yesterday's* work_date once the clock rolls over, so "today"
    // is the wrong key to look it up by. See WebClockInService.checkOut.
    //
    // "findFirst...OrderBy..." (LIMIT 1), not a bare uniqueness-assuming lookup — same reasoning
    // as AttendanceRepository.findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc:
    // a plain findBy...IsNull() throws IncorrectResultSizeDataAccessException the moment an
    // employee ever ends up with more than one open approved request, rather than just picking
    // the most recent one.
    Optional<WebClockInRequest> findFirstByEmployeeUserIdAndStatusAndCheckedOutAtIsNullOrderByWorkDateDesc(UUID employeeUserId, String status);
}
