package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PenalizationPolicyVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PenalizationPolicyVersionRepository extends JpaRepository<PenalizationPolicyVersion, UUID> {

    /** The row with no successor yet — i.e. the version Organization Masters shows/edits as "current". */
    Optional<PenalizationPolicyVersion> findByEffectiveToIsNull();

    // Backs PenalisationPolicyManagementService#list: the "current version" column for every
    // policy in one query instead of one findByPolicyIdAndEffectiveToIsNull round trip per policy.
    List<PenalizationPolicyVersion> findByPolicyIdInAndEffectiveToIsNull(Collection<UUID> policyIds);

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

    /**
     * Same "effective at" contract as {@link #findVersionsEffectiveAt}, scoped to one specific
     * policy — used once an employee's assigned {@code PenalisationPolicy} is known, so multiple
     * named policies can each have their own independent, currently-effective version.
     */
    @Query("SELECT v FROM PenalizationPolicyVersion v WHERE v.policyId = :policyId AND v.effectiveFrom <= :pointInTime "
            + "AND (v.effectiveTo IS NULL OR v.effectiveTo >= :pointInTime) ORDER BY v.version DESC")
    List<PenalizationPolicyVersion> findVersionsEffectiveAtForPolicy(
            @Param("policyId") UUID policyId, @Param("pointInTime") LocalDateTime pointInTime);

    List<PenalizationPolicyVersion> findByPolicyIdOrderByVersionDesc(UUID policyId);

    Optional<PenalizationPolicyVersion> findByPolicyIdAndEffectiveToIsNull(UUID policyId);

    long countByPolicyId(UUID policyId);
}
