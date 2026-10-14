package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Integer> {

    Optional<Permission> findByModule_CodeAndAction(String moduleCode, String action);
}
