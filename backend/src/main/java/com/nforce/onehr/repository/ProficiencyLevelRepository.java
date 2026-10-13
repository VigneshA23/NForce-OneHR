package com.nforce.onehr.repository;

import com.nforce.onehr.entity.ProficiencyLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProficiencyLevelRepository extends JpaRepository<ProficiencyLevel, UUID> {

    List<ProficiencyLevel> findByActiveTrueOrderBySortOrderAsc();

    List<ProficiencyLevel> findAllByOrderBySortOrderAsc();

    // CITEXT name column — case-insensitive at the DB level. Backs the migration scan's best-effort
    // match of an existing employee_skills.proficiency_level free-text value onto the master list.
    Optional<ProficiencyLevel> findByName(String name);
}
