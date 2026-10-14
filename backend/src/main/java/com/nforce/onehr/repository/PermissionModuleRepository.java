package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PermissionModule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PermissionModuleRepository extends JpaRepository<PermissionModule, Integer> {

    Optional<PermissionModule> findByCode(String code);
}
