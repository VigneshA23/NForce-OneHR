package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PlaceholderCheckinSeed;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// TEMPORARY — delete with FR-004 (see PlaceholderCheckinSeed entity Javadoc).
@Repository
public interface PlaceholderCheckinSeedRepository extends JpaRepository<PlaceholderCheckinSeed, UUID> {

    List<PlaceholderCheckinSeed> findByWorkDateBetween(LocalDate from, LocalDate to);

    List<PlaceholderCheckinSeed> findByEmployeeUserIdInAndWorkDateBetween(List<UUID> employeeUserIds, LocalDate from, LocalDate to);

    Optional<PlaceholderCheckinSeed> findByEmployeeUserIdAndWorkDate(UUID employeeUserId, LocalDate workDate);
}
