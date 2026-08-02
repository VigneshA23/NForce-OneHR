package com.nforce.onehr.dto.doc;

import com.nforce.onehr.entity.Announcement;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
public class AnnouncementResponse {
    Long id;
    String title;
    String body;
    String audience;
    Instant scheduledFor;
    Instant publishedAt;
    UUID createdBy;
    Instant createdAt;
    boolean published;
    boolean active;

    public static AnnouncementResponse from(Announcement a) {
        return new AnnouncementResponse(a.getId(), a.getTitle(), a.getBody(), a.getAudience(),
                a.getScheduledFor(), a.getPublishedAt(), a.getCreatedBy(), a.getCreatedAt(),
                a.getPublishedAt() != null, a.isActive());
    }
}
