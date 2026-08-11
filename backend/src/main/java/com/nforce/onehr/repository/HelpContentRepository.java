package com.nforce.onehr.repository;

import com.nforce.onehr.entity.HelpContent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Extends {@link JpaSpecificationExecutor} — same precedent as {@code HelpdeskTicketRepository}
 * — since the employee list (published+active, filtered by type/category/search) and the admin
 * list (everything, same filters plus published/active) share independently-optional filters
 * best combined at the DB level under real pagination.
 */
@Repository
public interface HelpContentRepository extends JpaRepository<HelpContent, UUID>, JpaSpecificationExecutor<HelpContent> {

    @Modifying
    @Query("UPDATE HelpContent h SET h.viewCount = h.viewCount + 1 WHERE h.id = :id")
    void incrementViewCount(@Param("id") UUID id);
}
