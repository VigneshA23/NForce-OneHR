package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AttendancePunch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttendancePunchRepository extends JpaRepository<AttendancePunch, UUID> {

    List<AttendancePunch> findByAttendanceRecordIdOrderByCheckInAtAsc(UUID attendanceRecordId);

    Optional<AttendancePunch> findByAttendanceRecordIdAndCheckOutAtIsNull(UUID attendanceRecordId);

    // Backs the "Frequent Breaks" negligence panel (ONEHR-107) — pulls every session for a
    // batch of attendance records (already scoped to a manager's team + date range) in one
    // query, grouped/aggregated in the service layer since punches carry no employeeUserId/workDate.
    List<AttendancePunch> findByAttendanceRecordIdInOrderByCheckInAtAsc(Collection<UUID> attendanceRecordIds);
}
