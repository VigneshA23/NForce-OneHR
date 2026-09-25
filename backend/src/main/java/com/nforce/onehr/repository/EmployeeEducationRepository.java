package com.nforce.onehr.repository;

import com.nforce.onehr.entity.EmployeeEducation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EmployeeEducationRepository extends JpaRepository<EmployeeEducation, UUID> {

    List<EmployeeEducation> findByEmployeeUserIdOrderByStartDateDesc(UUID employeeUserId);
}
