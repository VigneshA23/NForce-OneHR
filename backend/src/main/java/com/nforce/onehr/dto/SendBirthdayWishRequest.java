package com.nforce.onehr.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class SendBirthdayWishRequest {
    @NotNull
    private UUID toUserId;

    @NotBlank
    @Size(max = 500)
    private String message;
}
