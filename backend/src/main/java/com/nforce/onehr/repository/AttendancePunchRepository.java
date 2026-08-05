package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AttendancePunch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttendancePunchRepository extends JpaRepository<AttendancePunch, UUID> {

    List<AttendancePunch> findByAttendanceRecordIdOrderByCheckInAtAsc(UUID attendanceRecordId);

    Optional<AttendancePunch> findByAttendanceRecordIdAndCheckOutAtIsNull(UUID attendanceRecordId);
}
