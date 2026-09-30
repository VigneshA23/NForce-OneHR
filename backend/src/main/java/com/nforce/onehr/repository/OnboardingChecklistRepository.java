package com.nforce.onehr.repository;

import com.nforce.onehr.entity.OnboardingChecklist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OnboardingChecklistRepository extends JpaRepository<OnboardingChecklist, UUID> {
    Optional<OnboardingChecklist> findByEmployeeUserId(UUID employeeUserId);
    boolean existsByEmployeeUserId(UUID employeeUserId);

    // Backs OnboardingService#listQueue — the HR-wide onboarding queue. A dedicated method
    // rather than overriding findAll() itself (which #eligibleEmployees also calls, unrelated to
    // this fix), joining User to exclude a checklist whose employee has since been soft-deleted.
    @Query("SELECT c FROM OnboardingChecklist c JOIN User u ON u.id = c.employeeUserId WHERE u.deletedAt IS NULL")
    List<OnboardingChecklist> findAllWithActiveEmployee();
}
