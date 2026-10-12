package com.nforce.onehr.repository;

import com.nforce.onehr.entity.EmployeeLearningEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface EmployeeLearningEntryRepository extends JpaRepository<EmployeeLearningEntry, UUID> {

    List<EmployeeLearningEntry> findByEmployeeUserIdOrderByCreatedAtDesc(UUID employeeUserId);

    // Backs the Team Performance roster's inline learning items — one query for the whole team
    // instead of one per direct report.
    List<EmployeeLearningEntry> findByEmployeeUserIdInOrderByCreatedAtDesc(Collection<UUID> employeeUserIds);
}
