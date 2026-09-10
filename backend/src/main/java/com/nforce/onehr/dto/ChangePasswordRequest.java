package com.nforce.onehr.dto;

import com.nforce.onehr.validation.PasswordPolicy;
import com.nforce.onehr.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangePasswordRequest {

    // Not @NotBlank: omitted by the frontend for the forced (temp-password) flow, where the
    // user already proved they know it by using it to log in — see AuthService#changePassword,
    // which requires it explicitly only for the voluntary (already-authenticated) flow.
    private String currentPassword;

    @NotBlank(message = "New password is required")
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = PasswordPolicy.LENGTH_MESSAGE)
    @ValidPassword
    private String newPassword;

    @NotBlank(message = "Password confirmation is required")
    private String confirmPassword;
}
