package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data @Builder
public class NotificationDto {
    private Long id;
    private String type;
    private String title;
    private String message;
    private String linkPath;
    private boolean read;
    private String priority;
    private Instant createdAt;
}
