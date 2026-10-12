package com.nforce.onehr.repository;

import com.nforce.onehr.entity.SkillMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SkillMasterRepository extends JpaRepository<SkillMaster, UUID> {

    // CITEXT name column — Postgres itself does the case-insensitive match, so this is just an
    // ordinary equality lookup from the JPA side.
    Optional<SkillMaster> findByName(String name);

    List<SkillMaster> findByActiveTrueOrderByNameAsc();

    List<SkillMaster> findAllByOrderByNameAsc();
}
