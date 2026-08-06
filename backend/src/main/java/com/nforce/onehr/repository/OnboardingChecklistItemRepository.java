package com.nforce.onehr.repository;

import com.nforce.onehr.entity.OnboardingChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OnboardingChecklistItemRepository extends JpaRepository<OnboardingChecklistItem, UUID> {
    List<OnboardingChecklistItem> findByChecklistIdOrderByDueDateAsc(UUID checklistId);
}
