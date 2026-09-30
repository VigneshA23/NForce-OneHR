package com.nforce.onehr.ai.repository;

import com.nforce.onehr.ai.entity.AiBillingSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AiBillingSettingsRepository extends JpaRepository<AiBillingSettings, UUID> {

    Optional<AiBillingSettings> findBySingletonTrue();
}
