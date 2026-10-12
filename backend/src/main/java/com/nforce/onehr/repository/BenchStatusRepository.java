package com.nforce.onehr.repository;

import com.nforce.onehr.entity.BenchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BenchStatusRepository extends JpaRepository<BenchStatus, UUID> {

    boolean existsByEmployeeUserIdAndActiveTrue(UUID employeeUserId);

    /** US-A02: the active bench rows for an already-scoped set of employees. */
    List<BenchStatus> findByActiveTrueAndEmployeeUserIdIn(Collection<UUID> employeeUserIds);
}
