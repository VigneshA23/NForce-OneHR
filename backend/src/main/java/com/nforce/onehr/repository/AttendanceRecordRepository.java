package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AttendanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, UUID> {

    Optional<AttendanceRecord> findByUserIdAndAttendanceDate(UUID userId, LocalDate attendanceDate);

    List<AttendanceRecord> findByUserIdOrderByAttendanceDateDesc(UUID userId);
}
