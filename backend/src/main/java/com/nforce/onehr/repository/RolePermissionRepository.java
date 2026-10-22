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
            + "join fetch rp.role "
            + "where rp.role.id in :roleIds")
    List<RolePermission> findByRoleIdsWithPermissionAndModule(@Param("roleIds") Collection<Integer> roleIds);

    /** [roleId, grantCount] per role — the Roles overview's permission-count column, in one query. */
    @Query("select rp.role.id, count(rp) from RolePermission rp group by rp.role.id")
    List<Object[]> countByRole();

    void deleteByRole_Id(Integer roleId);

    /** Active, non-deleted users holding an ACTIVE role that grants module:action at one of {@code scopes}. */
    @Query("select distinct u.id from User u join u.roles r, RolePermission rp "
            + "where rp.role = r and r.active = true and u.active = true and u.deletedAt is null "
            + "and rp.permission.module.code = :module and rp.permission.action = :action and rp.scope in :scopes")
    java.util.Set<java.util.UUID> findActiveUserIdsWithGrant(@Param("module") String module, @Param("action") String action,
                                                             @Param("scopes") Collection<String> scopes);
}
