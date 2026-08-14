package com.nforce.onehr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.Instant;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiError {
    private final String message;
    private final Instant timestamp = Instant.now();
    private final String code;
    private final Instant lockedUntil;

    public ApiError(String message) {
        this(message, null, null);
    }

    public ApiError(String message, String code, Instant lockedUntil) {
        this.message = message;
        this.code = code;
        this.lockedUntil = lockedUntil;
    }
}
