package com.nforce.onehr.dto.talent;

import com.nforce.onehr.entity.SkillRequest;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
public class SkillRequestResponse {
    UUID id;
    UUID requestedByUserId;
    String requesterName;
    String name;
    String category;
    String note;
    String status;
    UUID mappedToSkillId;
    String mappedSkillName;
    UUID reviewedBy;
    Instant reviewedAt;
    Instant createdAt;

    public static SkillRequestResponse from(SkillRequest r, String requesterName, String mappedSkillName) {
        return new SkillRequestResponse(r.getId(), r.getRequestedByUserId(), requesterName, r.getName(),
                r.getCategory(), r.getNote(), r.getStatus(), r.getMappedToSkillId(), mappedSkillName,
                r.getReviewedBy(), r.getReviewedAt(), r.getCreatedAt());
    }
}
