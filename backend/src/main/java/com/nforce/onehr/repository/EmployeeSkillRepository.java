package com.nforce.onehr.repository;

import com.nforce.onehr.entity.EmployeeSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface EmployeeSkillRepository extends JpaRepository<EmployeeSkill, UUID> {

    List<EmployeeSkill> findByEmployeeUserIdOrderByCreatedAtAsc(UUID employeeUserId);

    // Backs the Team Performance roster's inline skill chips — one query for the whole team
    // instead of one per direct report.
    List<EmployeeSkill> findByEmployeeUserIdInOrderByCreatedAtAsc(Collection<UUID> employeeUserIds);
}
