package com.nforce.onehr.repository;

import com.nforce.onehr.entity.PayslipSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

// payGroupId IS the primary key, so plain findById/existsById already is "find by pay group".
@Repository
public interface PayslipSettingsRepository extends JpaRepository<PayslipSettings, UUID> {
}
