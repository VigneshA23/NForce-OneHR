package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PenalizationPolicyWorkHoursTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PenalizationPolicyWorkHoursTierRepository extends JpaRepository<PenalizationPolicyWorkHoursTier, UUID> {

    List<PenalizationPolicyWorkHoursTier> findByPolicyVersionIdOrderBySortOrderAsc(UUID policyVersionId);

    void deleteByPolicyVersionId(UUID policyVersionId);
}
