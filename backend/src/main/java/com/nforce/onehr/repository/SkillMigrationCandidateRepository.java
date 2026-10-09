package com.nforce.onehr.repository;

import com.nforce.onehr.entity.SkillMigrationCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SkillMigrationCandidateRepository extends JpaRepository<SkillMigrationCandidate, UUID> {

    Optional<SkillMigrationCandidate> findByNormalizedText(String normalizedText);

    List<SkillMigrationCandidate> findByStatusOrderByOccurrenceCountDesc(String status);
}
