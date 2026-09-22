package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data @Builder
public class ProfileTimelineEvent {
    private String type;   // "JOINED_COMPANY" | "MANAGER_CHANGED"
    private LocalDate date;
    private String description;
}
