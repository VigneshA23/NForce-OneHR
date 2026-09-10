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
    // @Email alone accepts a syntactically well-formed but non-existent TLD like
    // "gmail.comabc" (indistinguishable from a real one without a public-suffix-list lookup).
    // This blocks the specific reported case: once the domain reads ".com", nothing may follow.
    @Pattern(regexp = "^(?!.*\\.com[A-Za-z]).*$", message = "Personal email format is invalid")
    private String personalEmail;
    private String address;
    @Pattern(regexp = "^[A-Za-z]+(?:[ '.-][A-Za-z]+)*$",
            message = "Contact name can only contain letters, spaces, hyphens, apostrophes, and periods")
    private String emergencyContactName;
    @Pattern(regexp = "^\\d{10}$", message = "Contact phone number must be exactly 10 digits")
    private String emergencyContactPhone;
    private String workMode;
}
