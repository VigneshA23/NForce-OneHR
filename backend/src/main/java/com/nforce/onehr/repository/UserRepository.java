package com.nforce.onehr.repository;

import com.nforce.onehr.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("SELECT u FROM User u WHERE u.email = :email AND u.deletedAt IS NULL")
    Optional<User> findByEmail(String email);

    @Query("SELECT u FROM User u WHERE u.email = :email AND u.active = true AND u.deletedAt IS NULL")
    Optional<User> findActiveByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailAndDeletedAtIsNull(String email);

    @Query("SELECT DISTINCT u.id FROM User u JOIN u.roles r WHERE r.code IN ('HR_ADMIN', 'SUPER_ADMIN') AND u.deletedAt IS NULL")
    Set<UUID> findAdminUserIds();

    // Backs audit-log actor/target search: resolves a free-text name/email fragment to candidate
    // user ids without requiring a @ManyToOne join on AuditLog (which has none).
    @Query("""
            SELECT u.id FROM User u LEFT JOIN Employee e ON e.userId = u.id
            WHERE u.deletedAt IS NULL
              AND (LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.fullName) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Set<UUID> findUserIdsByEmailOrFullNameContaining(@Param("q") String q);

    // Mirrors EmployeeService.listEmployees()'s own definition of "employee" — holds the
    // EMPLOYEE role — so any dashboard filtering by this stays consistent with the
    // Employee Master page rather than re-deriving its own notion of who counts.
    @Query("SELECT DISTINCT u.id FROM User u JOIN u.roles r WHERE r.code = 'EMPLOYEE' AND u.deletedAt IS NULL")
    Set<UUID> findEmployeeRoleUserIds();
}
