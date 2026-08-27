package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PenalisationPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PenalisationPolicyRepository extends JpaRepository<PenalisationPolicy, UUID> {

    Optional<PenalisationPolicy> findByName(String name);

    // Backs PenalizationPolicyService#resolveDefaultPolicyId — "the org's original policy" is the
    // oldest by createdAt. A single ORDER BY ... LIMIT 1 query instead of fetching every policy
    // row into memory just to find the minimum.
    Optional<PenalisationPolicy> findFirstByOrderByCreatedAtAsc();
}
