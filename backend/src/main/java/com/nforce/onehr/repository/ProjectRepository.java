package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {
    List<Project> findAllByOrderByNameAsc();

    List<Project> findByOwnerEmployeeIdOrderByNameAsc(UUID ownerEmployeeId);

    /** Closes every ACTIVE project whose end date has passed; existing allocations are untouched. */
    @Modifying
    @Query("UPDATE Project p SET p.status = com.nforce.onehr.entity.ProjectStatus.CLOSED "
            + "WHERE p.status = com.nforce.onehr.entity.ProjectStatus.ACTIVE AND p.endDate IS NOT NULL AND p.endDate < :today")
    int closeExpired(@Param("today") LocalDate today);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, UUID id);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);
}
