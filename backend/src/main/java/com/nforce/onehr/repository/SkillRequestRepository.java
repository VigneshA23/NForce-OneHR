package com.nforce.onehr.repository;

import com.nforce.onehr.entity.SkillRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SkillRequestRepository extends JpaRepository<SkillRequest, UUID> {

    List<SkillRequest> findByRequestedByUserIdOrderByCreatedAtDesc(UUID requestedByUserId);

    List<SkillRequest> findByStatusOrderByCreatedAtAsc(String status);
}
