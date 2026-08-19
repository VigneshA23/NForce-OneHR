package com.nforce.onehr.repository;

import com.nforce.onehr.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    // Help Content approver-resolution fallback: the final authority when an author's reporting
    // chain has no active manager. Ordered by createdAt for a deterministic pick.
    @Query("SELECT u FROM User u JOIN u.roles r WHERE r.code = 'SUPER_ADMIN' AND u.active = true AND u.deletedAt IS NULL ORDER BY u.createdAt ASC")
    List<User> findActiveSuperAdmins();

    // Backs audit-log actor/target search: resolves a free-text name/email fragment to candidate
    // user ids without requiring a @ManyToOne join on AuditLog (which has none).
    @Query("""
            SELECT u.id FROM User u LEFT JOIN Employee e ON e.userId = u.id
            WHERE u.deletedAt IS NULL
              AND (LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.fullName) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Set<UUID> findUserIdsByEmailOrFullNameContaining(@Param("q") String q);

    // Exception Dashboard subjects: accounts holding EMPLOYEE and none of
    // MANAGER/HR_ADMIN/SUPER_ADMIN. A plain "holds EMPLOYEE" whitelist isn't enough —
    // some accounts (e.g. an HR Admin or Manager also granted EMPLOYEE so they can
    // punch in/out themselves) hold EMPLOYEE alongside an admin/manager role, and must
    // still never appear as exception subjects, company-wide or as a direct report.
    @Query("""
            SELECT DISTINCT u.id FROM User u JOIN u.roles r WHERE r.code = 'EMPLOYEE' AND u.deletedAt IS NULL
            AND u.id NOT IN (
                SELECT u2.id FROM User u2 JOIN u2.roles r2 WHERE r2.code IN ('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')
            )
            """)
    Set<UUID> findEmployeeRoleUserIds();
}
