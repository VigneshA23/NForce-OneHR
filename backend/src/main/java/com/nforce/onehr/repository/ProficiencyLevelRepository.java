package com.nforce.onehr.repository;

import com.nforce.onehr.entity.ProficiencyLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProficiencyLevelRepository extends JpaRepository<ProficiencyLevel, UUID> {

    List<ProficiencyLevel> findByActiveTrueOrderBySortOrderAsc();

    List<ProficiencyLevel> findAllByOrderBySortOrderAsc();
}
