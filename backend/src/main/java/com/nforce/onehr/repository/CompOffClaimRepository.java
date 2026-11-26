package com.nforce.onehr.repository;

import com.nforce.onehr.entity.CompOffClaim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompOffClaimRepository extends JpaRepository<CompOffClaim, UUID> {
    Optional<CompOffClaim> findByEmployeeUserIdAndWorkDate(UUID employeeUserId, LocalDate workDate);

    List<CompOffClaim> findByEmployeeUserIdOrderByWorkDateDesc(UUID employeeUserId);

    List<CompOffClaim> findByEmployeeUserIdInAndWorkDateBetween(Collection<UUID> employeeUserIds, LocalDate from, LocalDate to);

    List<CompOffClaim> findByWorkDateBetweenOrderByWorkDateDesc(LocalDate from, LocalDate to);
}
