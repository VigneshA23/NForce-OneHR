package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PenalizationPolicyVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PenalizationPolicyVersionRepository extends JpaRepository<PenalizationPolicyVersion, UUID> {

    /** The row with no successor yet — i.e. the version Organization Masters shows/edits as "current". */
    Optional<PenalizationPolicyVersion> findByEffectiveToIsNull();

    List<PenalizationPolicyVersion> findAllByOrderByVersionDesc();

    /**
     * The version in effect at {@code pointInTime} (pass the attendance date's start-of-day) —
     * read fresh from the database on every call, no caching, so a saved Organization Masters
     * change is visible to the very next evaluation (Penalization Policy's core "database-driven,
     * no synchronization mechanism" requirement).
     */
    @Query("SELECT v FROM PenalizationPolicyVersion v WHERE v.effectiveFrom <= :pointInTime "
            + "AND (v.effectiveTo IS NULL OR v.effectiveTo >= :pointInTime) ORDER BY v.version DESC")
    List<PenalizationPolicyVersion> findVersionsEffectiveAt(@Param("pointInTime") LocalDateTime pointInTime);
}
