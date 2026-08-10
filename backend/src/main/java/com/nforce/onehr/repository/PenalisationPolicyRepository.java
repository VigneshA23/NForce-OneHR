package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PenalisationPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PenalisationPolicyRepository extends JpaRepository<PenalisationPolicy, UUID> {

    Optional<PenalisationPolicy> findByName(String name);
}
