package com.nforce.onehr.repository;

import com.nforce.onehr.entity.RegularizationApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RegularizationApprovalRepository extends JpaRepository<RegularizationApproval, UUID> {

    List<RegularizationApproval> findByRequestIdOrderByActionDateDesc(UUID requestId);
}
