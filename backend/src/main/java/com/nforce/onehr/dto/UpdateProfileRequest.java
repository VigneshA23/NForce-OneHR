package com.nforce.onehr.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;

@Data
public class UpdateProfileRequest {
    // "^$|..." — optional field; see emergencyContactName below for why @Pattern needs this to
    // actually treat a blank value as "not provided" rather than "invalid".
    @Pattern(regexp = "^$|^\\d{10}$", message = "Phone number must be exactly 10 digits")
    private String phone;
    private LocalDate dateOfBirth;
    // A JSON key that's simply absent from the request and one explicitly sent as null are both
    // indistinguishable once deserialized — dateOfBirth would be null either way, so the update
    // logic below could never tell "this modal didn't touch the date" apart from "this modal
    // explicitly cleared it." This flag makes "clear it" an unambiguous, explicit instruction
    // instead, set true only by the one caller (Primary Details) that owns this field.
    private boolean clearDateOfBirth;
    private String gender;
    @Email(message = "Personal email format is invalid")
    // @Email alone accepts a syntactically well-formed but non-existent TLD like
    // "gmail.comabc" (indistinguishable from a real one without a public-suffix-list lookup).
    // This blocks the specific reported case: once the domain reads ".com", nothing may follow.
    @Pattern(regexp = "^(?!.*\\.com[A-Za-z]).*$", message = "Personal email format is invalid")
    private String personalEmail;
    private String address;
    // "^$|..." — this is an optional field; @Pattern already treats a null value as valid, but
    // an EMPTY STRING is not null and was still being run through the regex below, which
    // requires at least one letter and so rejected it. The "^$|" alternative explicitly accepts
    // blank too, so leaving the field empty is treated as "not provided", not "invalid".
    @Pattern(regexp = "^$|^[A-Za-z]+(?:[ '.-][A-Za-z]+)*$",
            message = "Contact name can only contain letters, spaces, hyphens, apostrophes, and periods")
    private String emergencyContactName;
    @Pattern(regexp = "^$|^\\d{10}$", message = "Contact phone number must be exactly 10 digits")
    private String emergencyContactPhone;
    private String workMode;

    // ESS "My Profile" redesign — additional self-service fields. No format validators beyond
    // required-ness are applied here (no existing precedent in this codebase to match), consistent
    // with the "plain columns, UI masking only" decision for the PII fields below.
    //
    // firstName/lastName deliberately do NOT get the "^$|" blank-allowed escape hatch middleName
    // has below — a profile can't exist without a name. This still can't break any OTHER modal's
    // save (Contact/Addresses/Identity never include these keys at all, and @Pattern only runs
    // against a key that's actually present — an absent/null key always passes regardless of
    // regex): only PrimaryDetailsModal ever sends firstName/lastName, and it always sends both.
    @Pattern(regexp = "^[A-Za-z]+(?:[ '.-][A-Za-z]+)*$",
            message = "Name can only contain letters, spaces, hyphens, apostrophes, and periods")
    private String firstName;
    @Pattern(regexp = "^$|^[A-Za-z]+(?:[ '.-][A-Za-z]+)*$",
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
    // Same reasoning as clearDateOfBirth above.
    private boolean clearPassportExpiry;
    private String bankAccountNumber;
    private String bankName;
    private String bankIfsc;
    private String nationalId;

    // US-C15/US-C21. PAN has one universal, well-known format regardless of company/state (unlike
    // Professional Tax); UAN is always a 12-digit PF account number. Both get the same "^$|..."
    // blank-allowed escape hatch as the other optional fields above — ProfileService only applies
    // a field when the key is actually present, so a blank value here is never sent in the first
    // place (see IdentityStatutoryModal's own "only send if replacing" comment), but the pattern
    // must still not reject an absent/blank value on its own.
    @Pattern(regexp = "^$|^[A-Z]{5}[0-9]{4}[A-Z]$", message = "PAN must be in the format ABCDE1234F")
    private String panNumber;
    @Pattern(regexp = "^$|^[0-9]{12}$", message = "UAN must be exactly 12 digits")
    private String uanNumber;
    // ESI number format varies enough across states/schemes that no single regex is reliable.
    private String esiNumber;
}
