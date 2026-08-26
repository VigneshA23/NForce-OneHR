package com.nforce.onehr.dto.helpcontent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateHelpContentRequest {

    @NotBlank(message = "Title is required")
    private String title;

    private String description;
    private String body;
    private String category;
    private boolean featured;
    private int displayOrder;
}
