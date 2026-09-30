package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @Builder
public class ProfileTimelineEvent {
    private String type;   // "JOINED_COMPANY" | "MANAGER_CHANGED"
    private LocalDate date;
    // Full-precision moment behind `date`, for ordering same-day events correctly — `date` alone
    // can't distinguish two changes made hours apart on the same calendar day.
    private LocalDateTime timestamp;
    private String description;
}
