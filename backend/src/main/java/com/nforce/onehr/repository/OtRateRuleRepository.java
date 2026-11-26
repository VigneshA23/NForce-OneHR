package com.nforce.onehr.repository;

import com.nforce.onehr.entity.OtRateRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OtRateRuleRepository extends JpaRepository<OtRateRule, UUID> {
    List<OtRateRule> findByActiveTrue();
    List<OtRateRule> findAllByOrderByEffectiveFromDescNameAsc();
}
