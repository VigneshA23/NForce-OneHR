package com.nforce.onehr.repository;

import com.nforce.onehr.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
}
