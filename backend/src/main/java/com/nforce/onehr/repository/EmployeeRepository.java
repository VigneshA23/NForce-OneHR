package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    @Query("SELECT e FROM Employee e JOIN FETCH e.user u LEFT JOIN FETCH e.department LEFT JOIN FETCH e.designation LEFT JOIN FETCH e.location")
    List<Employee> findAllWithDetails();

    boolean existsByEmployeeCode(String employeeCode);

    @Query("SELECT e.employeeCode FROM Employee e ORDER BY e.employeeCode DESC LIMIT 1")
    Optional<String> findMaxEmployeeCode();

    Optional<Employee> findByUser_Email(String email);
}
