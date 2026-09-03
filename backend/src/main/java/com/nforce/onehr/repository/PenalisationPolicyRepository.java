package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PenalisationPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PenalisationPolicyRepository extends JpaRepository<PenalisationPolicy, UUID> {

    Optional<PenalisationPolicy> findByName(String name);

    // Backs PenalizationPolicyService#resolveDefaultPolicyId — "the org's original policy" is the
    // oldest by createdAt. A single ORDER BY ... LIMIT 1 query instead of fetching every policy
    // row into memory just to find the minimum.
    Optional<PenalisationPolicy> findFirstByOrderByCreatedAtAsc();

    // Gap-001: the org default must never be an INACTIVE policy — see
    // PenalizationPolicyService#resolveDefaultPolicyId.
    Optional<PenalisationPolicy> findFirstByStatusOrderByCreatedAtAsc(String status);

    // Gap-001: bulk-loaded once per resolution pass by PenalizationPolicyResolutionService, rather
    // than checking one policy's status at a time per employee.
    List<PenalisationPolicy> findByStatus(String status);

    // Section 7: the admin-chosen org-wide fallback — at most one row can ever match (V152's
    // partial unique index), backing PenalizationPolicyService#resolveActiveDefaultPolicyId.
    Optional<PenalisationPolicy> findByOrgDefaultTrue();

    // Backs PenalisationPolicyManagementService#setOrgDefault: a bulk UPDATE executes immediately
    // (not deferred to flush, unlike loading the previous-default entity and calling save() on it)
    // so the old default is guaranteed cleared in the database before the new one is ever written —
    // Hibernate's flush ordering is based on entity LOAD order, not statement call order, so
    // loading `policy` (the new default) before `previous` (the old one) could otherwise flush
    // "true" before "false" and trip idx_penalisation_policies_one_org_default even though the
    // service code calls them in the right sequence. Also self-heals if more than one row was ever
    // left true by some earlier inconsistency.
    @Modifying
    @Query("UPDATE PenalisationPolicy p SET p.orgDefault = false WHERE p.orgDefault = true")
    void clearOrgDefault();
}
