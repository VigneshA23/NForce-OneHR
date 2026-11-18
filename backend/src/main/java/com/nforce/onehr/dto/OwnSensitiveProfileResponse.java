package com.nforce.onehr.dto;

/** The caller's own values that the profile shows masked (bank account, national ID, passport number, IFSC), unmasked, for the eye button. */
public record OwnSensitiveProfileResponse(String bankAccountNumber, String nationalId, String passportNumber, String bankIfsc) {}
