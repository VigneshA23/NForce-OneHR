package com.nforce.onehr.repository;

import com.nforce.onehr.entity.OnboardingChecklist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OnboardingChecklistRepository extends JpaRepository<OnboardingChecklist, UUID> {
    Optional<OnboardingChecklist> findByEmployeeUserId(UUID employeeUserId);
    boolean existsByEmployeeUserId(UUID employeeUserId);
}
