package com.nforce.onehr.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class SendKudosRequest {
    @NotNull
    private UUID toUserId;

    @NotBlank
    private String category;

    /** Optional — the composer allows sending with just a category picked. */
    private String note;
}
