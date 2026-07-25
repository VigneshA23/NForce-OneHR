package com.nforce.onehr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.Instant;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiError {
    private final String message;
    private final Instant timestamp = Instant.now();

    public ApiError(String message) {
        this.message = message;
    }
}
