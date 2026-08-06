package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Attendance;
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
public interface AttendanceRepository extends JpaRepository<Attendance, UUID> {

    Optional<Attendance> findByEmployeeUserIdAndWorkDate(UUID employeeUserId, LocalDate workDate);

    // Backs audit-log target search — resolves which attendance records belong to a set of employees.
    @Query("SELECT a.id FROM Attendance a WHERE a.employeeUserId IN :employeeUserIds")
    Set<UUID> findIdsByEmployeeUserIdIn(Collection<UUID> employeeUserIds);

    List<Attendance> findByEmployeeUserIdAndWorkDateBetweenOrderByWorkDateDesc(
            UUID employeeUserId, LocalDate from, LocalDate to);

    List<Attendance> findByWorkDate(LocalDate workDate);

    List<Attendance> findByWorkDateAndEmployeeUserIdIn(LocalDate workDate, List<UUID> employeeUserIds);

    List<Attendance> findByEmployeeUserIdInAndWorkDateBetween(List<UUID> employeeUserIds, LocalDate from, LocalDate to);
}
