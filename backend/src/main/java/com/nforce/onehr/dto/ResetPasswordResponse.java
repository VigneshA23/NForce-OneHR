package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class ResetPasswordResponse {
    private String tempPassword;
    private String message;
}
