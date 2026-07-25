package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChangePasswordResponse {
    private String token;
    private String message;
}
