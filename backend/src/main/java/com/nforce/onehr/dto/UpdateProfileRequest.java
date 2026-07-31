package com.nforce.onehr.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class UpdateProfileRequest {
    private String phone;
    private LocalDate dateOfBirth;
    private String gender;
    private String personalEmail;
    private String address;
    private String emergencyContactName;
    private String emergencyContactPhone;
    private String workMode;
}
