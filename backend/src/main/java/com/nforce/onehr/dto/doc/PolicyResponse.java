package com.nforce.onehr.dto.doc;

import com.nforce.onehr.entity.Policy;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
public class PolicyResponse {
    Long id;
    String title;
    String version;
    String description;
    String audience;
    boolean required;
    Instant publishedAt;
    UUID publishedBy;
    boolean active;
    Boolean acknowledged;
    Instant acknowledgedAt;

    public static PolicyResponse from(Policy p, Boolean acknowledged, Instant acknowledgedAt) {
        return new PolicyResponse(p.getId(), p.getTitle(), p.getVersion(), p.getDescription(),
                p.getAudience(), p.isRequired(), p.getPublishedAt(), p.getPublishedBy(), p.isActive(),
                acknowledged, acknowledgedAt);
    }

    public static PolicyResponse from(Policy p) {
        return from(p, null, null);
    }
}
