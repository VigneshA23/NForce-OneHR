package com.nforce.onehr.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure jakarta.validation.Validator tests for the {@code @ValidPassword} rule on
 * {@code newPassword}, consistent with {@code CreateHolidayRequestTest}.
 */
class ChangePasswordRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    private ChangePasswordRequest request(String newPassword) {
        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setCurrentPassword("CurrentPass1!");
        req.setNewPassword(newPassword);
        req.setConfirmPassword(newPassword);
        return req;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            " NewPassword1!",       // leading space
            "NewPassword1! ",       // trailing space
            "New Password1!",       // space in the middle
            "New Pass word 1!",     // multiple internal spaces
    })
    void rejectsPasswordsContainingAnySpace(String invalidPassword) {
        Set<ConstraintViolation<ChangePasswordRequest>> violations = validator.validate(request(invalidPassword));

        assertFalse(violations.isEmpty(), "expected a violation for: [" + invalidPassword + "]");
        assertTrue(
                violations.stream().anyMatch(v -> v.getMessage().equals("Password cannot contain spaces.")),
                "expected the space-specific message for: [" + invalidPassword + "], got: " + violations
        );
    }

    @Test
    void rejectsAllSpacePassword_asBlankRatherThanSpaceViolation() {
        // A password of only spaces is caught by @NotBlank before @ValidPassword ever runs.
        Set<ConstraintViolation<ChangePasswordRequest>> violations = validator.validate(request("        "));

        assertFalse(violations.isEmpty());
    }

    @Test
    void allowsPasswordWithoutSpaces() {
        Set<ConstraintViolation<ChangePasswordRequest>> violations = validator.validate(request("NewPassword1!"));

        assertTrue(violations.isEmpty(), "unexpected violations: " + violations);
    }

    @Test
    void rejectsPasswordBelowMinLength() {
        Set<ConstraintViolation<ChangePasswordRequest>> violations = validator.validate(request("Ab1!"));

        assertFalse(violations.isEmpty());
    }

    @Test
    void rejectsPasswordBelowComplexityThreshold() {
        // Only one character class (lowercase) present.
        Set<ConstraintViolation<ChangePasswordRequest>> violations = validator.validate(request("lowercaseonly"));

        assertFalse(violations.isEmpty());
    }
}
