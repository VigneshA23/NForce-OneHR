package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data @Builder
public class LeaveTypeResponse {
    private UUID id;
    private String code;
    private String name;
}
