// Single source of truth for the frontend's password policy. Mirrors the backend's
// PasswordPolicy (backend/src/main/java/com/nforce/onehr/validation/PasswordPolicy.java) so
// the client can give instant feedback, but the backend re-enforces every rule independently —
// this file only improves UX, it is never the security boundary.

export const PASSWORD_MIN_LENGTH = 8;
export const PASSWORD_MAX_LENGTH = 128;
export const PASSWORD_MIN_CHARACTER_CLASSES = 3;
export const PASSWORD_SPACE_MESSAGE = 'Password cannot contain spaces.';

/** True if the password contains a space anywhere — leading, trailing, or between characters. */
export function containsSpace(password: string): boolean {
  return password.includes(' ');
}

export const PASSWORD_CRITERIA = [
  { label: 'Uppercase letter', test: (p: string) => /[A-Z]/.test(p) },
  { label: 'Lowercase letter', test: (p: string) => /[a-z]/.test(p) },
  { label: 'Number', test: (p: string) => /[0-9]/.test(p) },
  { label: 'Special character', test: (p: string) => /[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>/?]/.test(p) },
];

export function passwordCharacterClassScore(password: string): number {
  return PASSWORD_CRITERIA.filter((c) => c.test(password)).length;
}

/** Drives the 4-bar strength meter; caps at "fair" until the length floor is met. */
export function passwordStrengthScore(password: string): number {
  if (!password) return 0;
  const classScore = passwordCharacterClassScore(password);
  return password.length < PASSWORD_MIN_LENGTH ? Math.min(classScore, 2) : classScore;
}

/**
 * Validates a new password against the full policy. Returns the first failing rule's
 * message, or null if the password is acceptable. Order matters: length and spaces are
 * checked before complexity so the user always sees the most fundamental problem first.
 */
export function validateNewPassword(password: string): string | null {
  if (!password) return 'New password is required';
  if (containsSpace(password)) {
    return PASSWORD_SPACE_MESSAGE;
  }
  if (password.length < PASSWORD_MIN_LENGTH) {
    return `Must be at least ${PASSWORD_MIN_LENGTH} characters`;
  }
  if (password.length > PASSWORD_MAX_LENGTH) {
    return `Must be no more than ${PASSWORD_MAX_LENGTH} characters`;
  }
  if (passwordCharacterClassScore(password) < PASSWORD_MIN_CHARACTER_CLASSES) {
    return `Must include at least ${PASSWORD_MIN_CHARACTER_CLASSES} of: uppercase, lowercase, number, special character`;
  }
  return null;
}
