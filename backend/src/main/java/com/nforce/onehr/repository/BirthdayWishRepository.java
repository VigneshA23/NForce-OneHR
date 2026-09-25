package com.nforce.onehr.repository;

import com.nforce.onehr.entity.BirthdayWish;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface BirthdayWishRepository extends JpaRepository<BirthdayWish, Long> {

    /** Wishes received since midnight today — backs the celebration card's "wishes received"
     * count/list, scoped to today's occasion rather than an ever-growing all-time history. */
    List<BirthdayWish> findByToUserIdAndCreatedAtAfterOrderByCreatedAtDesc(UUID toUserId, Instant since);

    long countByToUserIdAndCreatedAtAfter(UUID toUserId, Instant since);
}
