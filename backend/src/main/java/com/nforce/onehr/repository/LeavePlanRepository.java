package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeavePlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeavePlanRepository extends JpaRepository<LeavePlan, UUID> {

    List<LeavePlan> findAllByOrderByNameAsc();

    Optional<LeavePlan> findFirstByOrgDefaultTrue();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    /** Clears the current default first, so the partial unique index never sees two. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE LeavePlan p SET p.orgDefault = false WHERE p.orgDefault = true")
    int clearOrgDefault();
}
