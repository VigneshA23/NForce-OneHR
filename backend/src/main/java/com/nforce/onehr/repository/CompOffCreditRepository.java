package com.nforce.onehr.repository;

import com.nforce.onehr.entity.CompOffCredit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CompOffCreditRepository extends JpaRepository<CompOffCredit, UUID> {
    List<CompOffCredit> findByEmployeeUserIdOrderByEarnedOnDescCreatedAtDesc(UUID employeeUserId);

    List<CompOffCredit> findByEmployeeUserIdInOrderByEarnedOnDesc(Collection<UUID> employeeUserIds);

    List<CompOffCredit> findByEmployeeUserIdAndStatusOrderByExpiresOnAscCreatedAtAsc(UUID employeeUserId, String status);

    List<CompOffCredit> findByStatus(String status);

    List<CompOffCredit> findByStatusAndExpiresOnBefore(String status, LocalDate date);

    List<CompOffCredit> findByStatusAndExpiryNotifiedFalseAndExpiresOnBetween(String status, LocalDate from, LocalDate to);

    List<CompOffCredit> findAllByOrderByEarnedOnDesc();
}
