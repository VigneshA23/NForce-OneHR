package com.nforce.onehr.repository;

import com.nforce.onehr.dto.doc.PolicyListItem;
import com.nforce.onehr.entity.Policy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PolicyRepository extends JpaRepository<Policy, Long> {

    List<Policy> findByActiveTrueOrderByPublishedAtDesc();

    List<Policy> findAllByOrderByPublishedAtDesc();

    // Same rows as the two methods above, but skipping the attachmentData column (a BYTEA blob up
    // to 10MB) — these back the two list endpoints (myPolicies/listAll), which never need the
    // attachment bytes, only whether one exists (attachmentName != null). Without this, every
    // list load pulled every policy's full attachment over the wire from the DB.
    @Query("SELECT new com.nforce.onehr.dto.doc.PolicyListItem(p.id, p.title, p.version, p.description, "
         + "p.audience, p.required, p.publishedAt, p.publishedBy, p.active, p.versionNumber, "
         + "p.previousVersionId, p.attachmentName) FROM Policy p WHERE p.active = true ORDER BY p.publishedAt DESC")
    List<PolicyListItem> findActiveListItems();

    @Query("SELECT new com.nforce.onehr.dto.doc.PolicyListItem(p.id, p.title, p.version, p.description, "
         + "p.audience, p.required, p.publishedAt, p.publishedBy, p.active, p.versionNumber, "
         + "p.previousVersionId, p.attachmentName) FROM Policy p ORDER BY p.publishedAt DESC")
    List<PolicyListItem> findAllListItems();

    List<Policy> findByTitleOrderByPublishedAtDesc(String title);

    // Forward link in the version chain — the version (if any) that superseded this one. Backed
    // by publishNewVersion, which always sets previousVersionId on the new row (AC4).
    Optional<Policy> findByPreviousVersionId(Long previousVersionId);
}
