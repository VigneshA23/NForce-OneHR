package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    @Query("SELECT a FROM Announcement a WHERE a.publishedAt IS NOT NULL AND a.publishedAt <= :now AND a.active = true ORDER BY a.publishedAt DESC")
    List<Announcement> findPublished(Instant now);

    List<Announcement> findAllByOrderByCreatedAtDesc();
}
