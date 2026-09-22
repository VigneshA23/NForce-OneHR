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

    // ESS "My Profile" redesign — additional self-service fields. No format validators beyond
    // required-ness are applied here (no existing precedent in this codebase to match), consistent
    // with the "plain columns, UI masking only" decision for the PII fields below.
    @Pattern(regexp = "^[A-Za-z]+(?:[ '.-][A-Za-z]+)*$",
            message = "Name can only contain letters, spaces, hyphens, apostrophes, and periods")
    private String firstName;
    @Pattern(regexp = "^[A-Za-z]+(?:[ '.-][A-Za-z]+)*$",
            message = "Name can only contain letters, spaces, hyphens, apostrophes, and periods")
    private String middleName;
    @Pattern(regexp = "^[A-Za-z]+(?:[ '.-][A-Za-z]+)*$",
            message = "Name can only contain letters, spaces, hyphens, apostrophes, and periods")
    private String lastName;
    private String preferredName;
    private String bio;
    private String maritalStatus;
    private String emergencyContactRelationship;
    private String permanentAddress;
    private String passportNumber;
    private LocalDate passportExpiry;
    private String bankAccountNumber;
    private String bankName;
    private String bankIfsc;
    private String nationalId;
}
