package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, UUID> {

    Optional<Attendance> findByEmployeeUserIdAndWorkDate(UUID employeeUserId, LocalDate workDate);

    List<Attendance> findByEmployeeUserIdAndWorkDateBetweenOrderByWorkDateDesc(
            UUID employeeUserId, LocalDate from, LocalDate to);

    List<Attendance> findByWorkDate(LocalDate workDate);

    List<Attendance> findByWorkDateAndEmployeeUserIdIn(LocalDate workDate, List<UUID> employeeUserIds);

    List<Attendance> findByWorkDateBetween(LocalDate from, LocalDate to);

    List<Attendance> findByEmployeeUserIdInAndWorkDateBetween(List<UUID> employeeUserIds, LocalDate from, LocalDate to);
}
