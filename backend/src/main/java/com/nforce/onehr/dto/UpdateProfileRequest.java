package com.nforce.onehr.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;

@Data
public class UpdateProfileRequest {
    @Pattern(regexp = "^\\d{10}$", message = "Phone number must be exactly 10 digits")
    private String phone;
    private LocalDate dateOfBirth;
    private String gender;
    @Email(message = "Personal email format is invalid")
    private String personalEmail;
    private String address;
    private String emergencyContactName;
    @Pattern(regexp = "^\\d{10}$", message = "Contact phone number must be exactly 10 digits")
    private String emergencyContactPhone;
    private String workMode;
}
