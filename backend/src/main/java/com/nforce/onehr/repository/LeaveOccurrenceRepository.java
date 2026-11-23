package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeaveOccurrence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaveOccurrenceRepository extends JpaRepository<LeaveOccurrence, UUID> {

    /** The occurrence whose window contains {@code date} (start <= date <= windowEnd), if any. */
    @Query("SELECT o FROM LeaveOccurrence o WHERE o.employeeUserId = :employeeUserId AND o.leaveType.id = :leaveTypeId "
            + "AND o.startDate <= :date AND o.windowEndDate >= :date")
    Optional<LeaveOccurrence> findCovering(@Param("employeeUserId") UUID employeeUserId,
                                           @Param("leaveTypeId") UUID leaveTypeId,
                                           @Param("date") LocalDate date);

    List<LeaveOccurrence> findByEmployeeUserIdAndLeaveTypeIdOrderByStartDateDesc(UUID employeeUserId, UUID leaveTypeId);

    boolean existsByLeaveTypeId(UUID leaveTypeId);
}
