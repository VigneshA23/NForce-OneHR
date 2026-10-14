package com.nforce.onehr.repository;

import com.nforce.onehr.entity.ManagerRoleAutoGrant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** {@code userId} is itself the entity's @Id, so existsById/deleteById (inherited) are exactly
 *  "does this user hold an auto-granted MANAGER marker" / "remove it". */
public interface ManagerRoleAutoGrantRepository extends JpaRepository<ManagerRoleAutoGrant, UUID> {
}
