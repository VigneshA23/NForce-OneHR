package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data @Builder
public class KudosResponse {
    private Long id;
    private String fromUserId;
    private String fromName;
    private String toUserId;
    private String toName;
    private String category;
    private String note;
    private Instant createdAt;
}
