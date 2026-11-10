package com.nforce.onehr.repository;

import com.nforce.onehr.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    // Same role scope as findAdminUserIds (both HR_ADMIN and SUPER_ADMIN), but active-only — used
    // by HelpdeskService for anything that hands out *new* work (ticket-created notification
    // fan-out, assignment-target validation), where a deactivated admin shouldn't be paged or
    // assignable. Existing/historical assignments to a since-deactivated admin are untouched —
    // this only gates who can be picked for something new.
    @Query("SELECT DISTINCT u.id FROM User u JOIN u.roles r WHERE r.code IN ('HR_ADMIN', 'SUPER_ADMIN') AND u.active = true AND u.deletedAt IS NULL")
    Set<UUID> findActiveAdminUserIds();

    // Help Content approver-resolution fallback: the final authority when an author's reporting
    // chain has no active manager. Ordered by createdAt for a deterministic pick.
    @Query("SELECT u FROM User u JOIN u.roles r WHERE r.code = 'SUPER_ADMIN' AND u.active = true AND u.deletedAt IS NULL ORDER BY u.createdAt ASC")
    List<User> findActiveSuperAdmins();

    // Deliberately HR_ADMIN only (not findAdminUserIds, which also includes SUPER_ADMIN) — used
    // to notify HR of newly-submitted Regularization/WFH/Partial Day requests without also
    // paging every Super Admin on every single submission.
    @Query("SELECT DISTINCT u.id FROM User u JOIN u.roles r WHERE r.code = 'HR_ADMIN' AND u.active = true AND u.deletedAt IS NULL")
    Set<UUID> findActiveHrAdminUserIds();

    // Backs audit-log actor/target search: resolves a free-text name/email fragment to candidate
    // user ids without requiring a @ManyToOne join on AuditLog (which has none).
    @Query("""
            SELECT u.id FROM User u LEFT JOIN Employee e ON e.userId = u.id
            WHERE u.deletedAt IS NULL
              AND (LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.fullName) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Set<UUID> findUserIdsByEmailOrFullNameContaining(@Param("q") String q);

    // Backs EmployeeService.listPotentialManagers — same rows as plain findAll() (active-only
    // filtering still happens in the caller's stream, unchanged), just fetch-joined so it's one
    // query instead of one lazy "load roles" query per row (User.roles is EAGER, but without an
    // explicit join fetch that still means a secondary select per entity).
    @Query("SELECT DISTINCT u FROM User u LEFT JOIN FETCH u.roles")
    List<User> findAllWithRoles();

    // Batch equivalent of findById(id).getRoles() for a known set of ids — used to resolve e.g.
    // a reviewer's primary role for several rows in one query instead of one lazy "load roles"
    // query per distinct reviewer (User.roles is EAGER, so a plain findAllById would still incur
    // one roles select per returned row without this explicit join fetch).
    @Query("SELECT DISTINCT u FROM User u LEFT JOIN FETCH u.roles WHERE u.id IN :ids")
    List<User> findAllByIdWithRoles(@Param("ids") Collection<UUID> ids);

    // Attendance-policy subjects (Exception Dashboard, penalties): ordinary employees only —
    // accounts that hold none of MANAGER/HR_ADMIN/SUPER_ADMIN AND currently manage nobody. The
    // "manages nobody" half preserves today's behaviour for people who used to carry an
    // automatically granted MANAGER role (that role is no longer granted automatically), and the
    // whole rule is a business policy about who the attendance rules apply to — it deliberately no
    // longer depends on holding an EMPLOYEE role row (baseline access never did).
    @Query("""
            SELECT u.id FROM User u WHERE u.deletedAt IS NULL
            AND u.id NOT IN (
                SELECT u2.id FROM User u2 JOIN u2.roles r2 WHERE r2.code IN ('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')
            )
            AND u.id NOT IN (
                SELECT h.managerUserId FROM EmployeeManagerHistory h JOIN User e ON e.id = h.employeeUserId
                WHERE h.effectiveTo IS NULL AND e.deletedAt IS NULL
            )
            """)
    Set<UUID> findAttendancePolicySubjectIds();

    // Members of the default "Employee" category: live accounts with no assigned role other than
    // the baseline EMPLOYEE (which may or may not exist as a legacy user_roles row).
    @Query("""
            SELECT u FROM User u WHERE u.deletedAt IS NULL
            AND NOT EXISTS (SELECT 1 FROM User u2 JOIN u2.roles r WHERE u2.id = u.id AND r.code <> 'EMPLOYEE')
            """)
    List<User> findUsersWithoutAssignedRole();

    @Query("""
            SELECT COUNT(u) FROM User u WHERE u.deletedAt IS NULL
            AND NOT EXISTS (SELECT 1 FROM User u2 JOIN u2.roles r WHERE u2.id = u.id AND r.code <> 'EMPLOYEE')
            """)
    long countUsersWithoutAssignedRole();

    // [roleId, holderCount] for every role, excluding soft-deleted accounts — Roles overview's
    // "Assigned users" column in one query instead of one per role.
    @Query("SELECT r.id, COUNT(u) FROM User u JOIN u.roles r WHERE u.deletedAt IS NULL GROUP BY r.id")
    List<Object[]> countHoldersByRole();

    @Query("SELECT u FROM User u JOIN u.roles r WHERE r.id = :roleId AND u.deletedAt IS NULL")
    List<User> findHoldersOfRole(@Param("roleId") Integer roleId);

    // Every account regardless of role — the scope of the nightly attendance-exception emails,
    // which (unlike the Exceptions dashboard and penalties) go to Managers/HR/Super Admins too.
    @Query("SELECT u.id FROM User u WHERE u.deletedAt IS NULL")
    Set<UUID> findAllNonDeletedUserIds();
}
