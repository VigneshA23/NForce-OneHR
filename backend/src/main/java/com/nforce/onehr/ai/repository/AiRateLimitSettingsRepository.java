package com.nforce.onehr.ai.repository;

import com.nforce.onehr.ai.entity.AiRateLimitSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AiRateLimitSettingsRepository extends JpaRepository<AiRateLimitSettings, UUID> {

    Optional<AiRateLimitSettings> findBySingletonTrue();
}
