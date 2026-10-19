package com.nforce.onehr.repository;

import com.nforce.onehr.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.*;

@Repository
public interface EmployeeSkillRatingRepository extends JpaRepository<EmployeeSkillRating, UUID> {
    List<EmployeeSkillRating> findByEmployeeUserId(UUID employeeUserId);

    Optional<EmployeeSkillRating> findByEmployeeUserIdAndSkillId(UUID employeeUserId, UUID skillId);

    List<EmployeeSkillRating> findByEmployeeUserIdIn(Collection<UUID> employeeUserIds);
}
