package com.nforce.onehr.repository;

import com.nforce.onehr.entity.HelpContentAudience;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface HelpContentAudienceRepository extends JpaRepository<HelpContentAudience, UUID> {

    List<HelpContentAudience> findByContentId(UUID contentId);

    @Modifying
    @Query("DELETE FROM HelpContentAudience a WHERE a.contentId = :contentId")
    void deleteByContentId(@Param("contentId") UUID contentId);
}
