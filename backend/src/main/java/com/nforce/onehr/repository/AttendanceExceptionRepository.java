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

    // Backs Late Arrival's Total Hours basis (Section 25/29) — needs each occurrence's raw
    // minutesLate to compute the grace-excluded cumulative total, not just a count.
    List<AttendanceException> findByEmployeeUserIdAndExceptionTypeAndExceptionDateBetween(
            UUID employeeUserId, String exceptionType, LocalDate from, LocalDate to);

    // Backs ExceptionService#notifyUnnotifiedExceptions — the scheduled job is the only caller
    // that ever emails an employee about one of these occurrences, regardless of whether the row
    // itself was created moments ago by the same run or earlier by someone opening the Exceptions
    // dashboard. A row whose email failed stays un-notified and is picked up again on the next run;
    // the date floor bounds that retry (and any first-run backlog) so a permanently undeliverable
    // row doesn't retry forever and old history isn't mass-emailed.
    List<AttendanceException> findByExceptionTypeInAndNotifiedAtIsNullAndExceptionDateGreaterThanEqual(
            List<String> exceptionTypes, LocalDate notBefore);
}
