package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Kudos;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface KudosRepository extends JpaRepository<Kudos, Long> {

    List<Kudos> findByToUserIdOrderByCreatedAtDesc(UUID toUserId);

    List<Kudos> findByFromUserIdOrderByCreatedAtDesc(UUID fromUserId);

    long countByToUserId(UUID toUserId);
}
