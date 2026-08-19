package com.nforce.onehr.repository;

import com.nforce.onehr.entity.HelpContentAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface HelpContentAttachmentRepository extends JpaRepository<HelpContentAttachment, UUID> {

    List<HelpContentAttachment> findByContentIdOrderByDisplayOrderAsc(UUID contentId);

    @Modifying
    @Query("DELETE FROM HelpContentAttachment a WHERE a.contentId = :contentId")
    void deleteByContentId(@Param("contentId") UUID contentId);

    long countByContentId(UUID contentId);
}
