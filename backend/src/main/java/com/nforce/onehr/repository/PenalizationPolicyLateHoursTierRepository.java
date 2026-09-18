package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PenalizationPolicyLateHoursTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PenalizationPolicyLateHoursTierRepository extends JpaRepository<PenalizationPolicyLateHoursTier, UUID> {

    List<PenalizationPolicyLateHoursTier> findByPolicyVersionIdOrderBySortOrderAsc(UUID policyVersionId);

    void deleteByPolicyVersionId(UUID policyVersionId);
}
