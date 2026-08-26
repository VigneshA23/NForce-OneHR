package com.nforce.onehr.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordConstraintValidator implements ConstraintValidator<ValidPassword, String> {

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        if (password == null || password.isBlank()) return true; // @NotBlank handles null/blank

        if (PasswordPolicy.containsSpace(password)) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(PasswordPolicy.SPACE_MESSAGE)
                    .addConstraintViolation();
            return false;
        }

        return PasswordPolicy.characterClassScore(password) >= PasswordPolicy.MIN_CHARACTER_CLASSES;
    }
}
