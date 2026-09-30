package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Policy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PolicyRepository extends JpaRepository<Policy, Long> {

    List<Policy> findByActiveTrueOrderByPublishedAtDesc();

    List<Policy> findAllByOrderByPublishedAtDesc();

    List<Policy> findByTitleOrderByPublishedAtDesc(String title);

    // Forward link in the version chain — the version (if any) that superseded this one. Backed
    // by publishNewVersion, which always sets previousVersionId on the new row (AC4).
    Optional<Policy> findByPreviousVersionId(Long previousVersionId);
}
