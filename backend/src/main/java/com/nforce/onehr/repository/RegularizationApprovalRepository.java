package com.nforce.onehr.repository;

import com.nforce.onehr.entity.RegularizationApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface RegularizationApprovalRepository extends JpaRepository<RegularizationApproval, UUID> {

    List<RegularizationApproval> findByRequestIdOrderByActionDateDesc(UUID requestId);

    // Batch equivalent of the above — backs RegularizationService's list responses (listMine,
    // listPendingForApprover, listForApprover, listAll, getHistoryForManager), fetching every
    // request's approval history in one query instead of one per request.
    List<RegularizationApproval> findByRequestIdInOrderByActionDateDesc(Collection<UUID> requestIds);
}
