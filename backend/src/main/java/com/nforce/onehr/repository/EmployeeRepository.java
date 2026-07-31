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

    @Query("SELECT e FROM Employee e JOIN FETCH e.user u LEFT JOIN FETCH e.department LEFT JOIN FETCH e.designation LEFT JOIN FETCH e.location WHERE u.deletedAt IS NULL")
    List<Employee> findAllWithDetails();

    boolean existsByEmployeeCode(String employeeCode);

    @Query(value = "SELECT employee_code FROM employees WHERE employee_code ~ '^NF-[0-9]+$' ORDER BY CAST(SUBSTRING(employee_code FROM 4) AS INTEGER) DESC LIMIT 1", nativeQuery = true)
    Optional<String> findMaxNumericEmployeeCode();

    Optional<Employee> findByUser_Email(String email);

    @Query("SELECT e FROM Employee e JOIN FETCH e.user u LEFT JOIN FETCH e.department LEFT JOIN FETCH e.designation LEFT JOIN FETCH e.location WHERE u.deletedAt IS NULL AND u.active = true")
    List<Employee> findAllActiveWithDetails();
}
