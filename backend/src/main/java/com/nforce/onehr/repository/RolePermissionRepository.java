package com.nforce.onehr.repository;

import com.nforce.onehr.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {

    /** Every grant for the given roles, with permission+module eagerly fetched (avoids N+1 when
     *  computing a user's effective authorities — see CustomUserDetailsService). */
    @Query("select rp from RolePermission rp "
            + "join fetch rp.permission p "
            + "join fetch p.module "
            + "where rp.role.id in :roleIds")
    List<RolePermission> findByRoleIdsWithPermissionAndModule(@Param("roleIds") Collection<Integer> roleIds);
}
