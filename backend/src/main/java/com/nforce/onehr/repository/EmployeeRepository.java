package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    @Query("SELECT DISTINCT e FROM Employee e JOIN FETCH e.user u LEFT JOIN FETCH e.department LEFT JOIN FETCH e.designation LEFT JOIN FETCH e.location WHERE u.deletedAt IS NULL AND u.active = true AND u.id NOT IN (SELECT u2.id FROM User u2 JOIN u2.roles r WHERE r.code IN ('HR_ADMIN', 'SUPER_ADMIN'))")
    List<Employee> findAllActiveNonAdminWithDetails();

    @Query("SELECT DISTINCT e FROM Employee e JOIN FETCH e.user u LEFT JOIN FETCH e.department LEFT JOIN FETCH e.designation LEFT JOIN FETCH e.location WHERE u.deletedAt IS NULL AND u.active = true AND u.id IN (SELECT u2.id FROM User u2 JOIN u2.roles r WHERE r.code IN :roleCodes)")
    List<Employee> findActiveByRoleCodes(@Param("roleCodes") Set<String> roleCodes);

    @Query("SELECT COUNT(e) FROM Employee e JOIN e.user u WHERE e.department.id = :id AND u.deletedAt IS NULL")
    long countByDepartmentId(@Param("id") UUID id);

    @Query("SELECT COUNT(e) FROM Employee e JOIN e.user u WHERE e.designation.id = :id AND u.deletedAt IS NULL")
    long countByDesignationId(@Param("id") UUID id);

    @Query("SELECT COUNT(e) FROM Employee e JOIN e.user u WHERE e.location.id = :id AND u.deletedAt IS NULL")
    long countByLocationId(@Param("id") UUID id);

    @Query("SELECT e.userId, e.fullName FROM Employee e WHERE e.userId IN :ids")
    List<Object[]> findNamesByUserIds(@Param("ids") Set<UUID> ids);
}
