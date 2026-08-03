package com.nforce.onehr.dto.doc;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Instant;

@Data
public class CreateAnnouncementRequest {

    @NotBlank
    @Size(max = 200)
    private String title;

    @NotBlank
    private String body;

    @Size(max = 100)
    private String audience = "All Employees";

    private Instant scheduledFor;

    private boolean publishNow = false;
}
