package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Integer> {

    Optional<Permission> findByModule_CodeAndAction(String moduleCode, String action);

    /** The full registered catalogue — the only permissions a role can ever be granted. */
    @Query("select p from Permission p join fetch p.module m order by m.displayName, p.id")
    List<Permission> findAllWithModule();
}
