package com.nforce.onehr.dto.talent;

import com.nforce.onehr.entity.SkillMigrationCandidate;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
public class SkillMigrationCandidateResponse {
    UUID id;
    String rawText;
    String normalizedText;
    int occurrenceCount;
    UUID proposedSkillId;
    String proposedSkillName;
    String status;
    UUID resolvedSkillId;
    String resolvedSkillName;
    Instant reviewedAt;

    public static SkillMigrationCandidateResponse from(SkillMigrationCandidate c, String proposedName, String resolvedName) {
        return new SkillMigrationCandidateResponse(c.getId(), c.getRawText(), c.getNormalizedText(),
                c.getOccurrenceCount(), c.getProposedSkillId(), proposedName, c.getStatus(),
                c.getResolvedSkillId(), resolvedName, c.getReviewedAt());
    }
}
