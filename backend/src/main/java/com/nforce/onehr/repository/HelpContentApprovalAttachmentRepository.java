package com.nforce.onehr.repository;

import com.nforce.onehr.entity.HelpContentApprovalAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface HelpContentApprovalAttachmentRepository extends JpaRepository<HelpContentApprovalAttachment, UUID> {

    List<HelpContentApprovalAttachment> findByApprovalIdOrderByDisplayOrderAsc(UUID approvalId);
}
