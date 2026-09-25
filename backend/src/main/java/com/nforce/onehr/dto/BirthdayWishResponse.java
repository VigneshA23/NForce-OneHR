package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data @Builder
public class BirthdayWishResponse {
    private Long id;
    private String fromUserId;
    private String fromName;
    private String message;
    private Instant createdAt;
}
