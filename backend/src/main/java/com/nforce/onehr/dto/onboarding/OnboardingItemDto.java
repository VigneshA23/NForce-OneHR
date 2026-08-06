package com.nforce.onehr.dto.onboarding;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data @Builder
public class OnboardingItemDto {
    private UUID id;              // null for auto (non-persisted) items
    private String itemKey;
    private String label;
    private String category;      // PRE_BOARDING | SETUP | DOCUMENTS
    private boolean auto;         // true = derived from Documents/Assets, not manually checkable
    private String source;        // "Documents" | "Assets" | null
    private LocalDate dueDate;
    private boolean done;
    private Instant doneAt;
    private String doneByName;    // "System" for auto items
    private String meta;          // e.g. asset tag, or "2 of 4 required documents verified"
}
