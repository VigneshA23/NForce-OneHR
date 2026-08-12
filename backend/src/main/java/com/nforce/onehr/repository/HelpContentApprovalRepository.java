package com.nforce.onehr.repository;

import com.nforce.onehr.entity.HelpContentApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HelpContentApprovalRepository extends JpaRepository<HelpContentApproval, UUID> {

    long countByContentId(UUID contentId);

    Optional<HelpContentApproval> findByContentIdAndStatus(UUID contentId, String status);

    Optional<HelpContentApproval> findByContentIdAndAttemptNumber(UUID contentId, int attemptNumber);

    List<HelpContentApproval> findByContentIdOrderByAttemptNumberDesc(UUID contentId);

    List<HelpContentApproval> findByApproverIdAndStatus(UUID approverId, String status);

    List<HelpContentApproval> findByStatus(String status);
}
