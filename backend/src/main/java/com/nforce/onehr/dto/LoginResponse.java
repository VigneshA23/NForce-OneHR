package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginResponse {
    private String token;
    private boolean mustChangePassword;
    private String email;
    private String role;
}
