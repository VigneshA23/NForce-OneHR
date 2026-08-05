package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/** A single check-in/check-out session, as shown in a day's punch history. */
@Data @Builder
public class PunchResponse {
    private UUID id;
    private LocalDateTime checkInAt;
    private LocalDateTime checkOutAt;
}
