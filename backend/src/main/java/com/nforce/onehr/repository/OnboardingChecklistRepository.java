package com.nforce.onehr.repository;

import com.nforce.onehr.entity.OnboardingChecklist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    // Backs OnboardingService#completeOnboarding — an atomic conditional update instead of a
    // read-then-write, so two concurrent completion requests for the same checklist can't both
    // succeed: the WHERE clause re-checks status = IN_PROGRESS at the database, so only the first
    // writer gets rowsUpdated == 1; the loser gets 0 and is turned into a clean rejection by the
    // caller, never a second COMPLETED write or a second audit log entry. clearAutomatically
    // drops the already-loaded (now stale) checklist from the persistence context so the
    // caller's subsequent re-read reflects the just-written row rather than the pre-update one.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE OnboardingChecklist c SET c.status = 'COMPLETED', c.completedAt = :completedAt " +
           "WHERE c.id = :id AND c.status = 'IN_PROGRESS'")
    int completeIfInProgress(@Param("id") UUID id, @Param("completedAt") Instant completedAt);
}
