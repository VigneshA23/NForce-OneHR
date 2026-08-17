package com.nforce.onehr.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordConstraintValidator implements ConstraintValidator<ValidPassword, String> {

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        if (password == null || password.isBlank()) return true; // @NotBlank handles null/blank

        if (!password.equals(password.trim())) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(PasswordPolicy.WHITESPACE_MESSAGE)
                    .addConstraintViolation();
            return false;
        }

        return PasswordPolicy.characterClassScore(password) >= PasswordPolicy.MIN_CHARACTER_CLASSES;
    }
}
