package com.nforce.onehr.repository;

import com.nforce.onehr.entity.SuperAdminModuleFence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SuperAdminModuleFenceRepository extends JpaRepository<SuperAdminModuleFence, Long> {

    @Query("select f from SuperAdminModuleFence f join fetch f.module order by f.module.code, f.action")
    List<SuperAdminModuleFence> findAllWithModule();

    Optional<SuperAdminModuleFence> findByModule_CodeAndAction(String moduleCode, String action);
}
