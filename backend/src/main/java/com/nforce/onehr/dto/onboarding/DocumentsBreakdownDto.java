package com.nforce.onehr.dto.onboarding;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class DocumentsBreakdownDto {
    private String documentTypeName;
    private String status; // VERIFIED | PENDING_VERIFICATION | REJECTED | MISSING
}
