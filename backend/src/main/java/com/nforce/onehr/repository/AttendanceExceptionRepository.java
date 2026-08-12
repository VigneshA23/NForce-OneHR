package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AttendanceException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttendanceExceptionRepository extends JpaRepository<AttendanceException, UUID> {

    List<AttendanceException> findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(
            List<UUID> employeeUserIds, LocalDate from, LocalDate to);

    Optional<AttendanceException> findByEmployeeUserIdAndExceptionDateAndExceptionType(
            UUID employeeUserId, LocalDate exceptionDate, String exceptionType);

    // Backs ConfiguredAttendancePolicyEngine's exemption-period counts (Late Arrival's "exempt N
    // in a Month", Missing Logs' "exempt N days in a Month") and same-day interaction checks —
    // counting existing detected exceptions, not re-deriving attendance facts.
    long countByEmployeeUserIdAndExceptionTypeAndExceptionDateBetween(
            UUID employeeUserId, String exceptionType, LocalDate from, LocalDate to);

    boolean existsByEmployeeUserIdAndExceptionDateAndExceptionType(
            UUID employeeUserId, LocalDate exceptionDate, String exceptionType);
}
