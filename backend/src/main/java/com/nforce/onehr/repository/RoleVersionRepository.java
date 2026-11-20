package com.nforce.onehr.repository;

import com.nforce.onehr.entity.RoleVersion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoleVersionRepository extends JpaRepository<RoleVersion, Long> {

    Optional<RoleVersion> findTopByRoleIdOrderByVersionNoDesc(Integer roleId);

    Optional<RoleVersion> findByRoleIdAndVersionNo(Integer roleId, int versionNo);

    /** Newest first; the caller reads one extra row past the page to diff against its predecessor. */
    List<RoleVersion> findByRoleIdOrderByVersionNoDesc(Integer roleId, Pageable page);
}
