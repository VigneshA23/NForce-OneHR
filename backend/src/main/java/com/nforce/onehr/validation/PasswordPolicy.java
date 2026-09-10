package com.nforce.onehr.validation;

/**
 * Single source of truth for the platform's password policy. Referenced by
 * {@link ValidPassword}/{@link PasswordConstraintValidator} (Bean Validation, enforced on
 * every password-setting API request) so the rules can't drift between endpoints or between
 * a DTO annotation and hand-written service code. If the policy ever changes, change it here
 * only — every flow that sets a password (initial setup, change, and any future reset flow)
 * picks it up automatically via {@code @ValidPassword}.
 */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 128;
    public static final int MIN_CHARACTER_CLASSES = 3;

    public static final String LENGTH_MESSAGE =
            "Password must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters";
    public static final String SPACE_MESSAGE = "Password cannot contain spaces.";
    public static final String COMPLEXITY_MESSAGE =
            "Password must include at least " + MIN_CHARACTER_CLASSES
                    + " of: uppercase letter, lowercase letter, number, special character";

    /** True if the password contains a space anywhere — leading, trailing, or between characters. */
    public static boolean containsSpace(String password) {
        return password.indexOf(' ') >= 0;
    }

    /** Character-class score used by both the complexity check and the frontend strength meter. */
    public static int characterClassScore(String password) {
        int score = 0;
        if (password.chars().anyMatch(Character::isUpperCase)) score++;
        if (password.chars().anyMatch(Character::isLowerCase)) score++;
        if (password.chars().anyMatch(Character::isDigit)) score++;
        if (password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*")) score++;
        return score;
    }
}
