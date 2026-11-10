import type { CreateUserPayload } from '../../api/employees';

// Mirrors the backend's CreateUserRequest @Pattern checks (see UserManagementPage.tsx's own
// NAME_PATTERN/EMAIL_PATTERN comment) so both sides reject the same inputs.
export const NAME_PATTERN = /^(?=.*\p{L})[\p{L}\s'-]+$/u;
export const EMAIL_PATTERN = /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/;

/**
 * Step 1 Full Name auto-composition. While the admin hasn't directly edited the Full Name field
 * (`nameTouched` false), the field tracks the typed name parts; the moment they type into Full
 * Name directly, the caller sets `nameTouched` true and this function returns `current` unchanged
 * forever after — a manual edit is never silently overwritten.
 */
export function composeFullName(firstName: string, middleName: string, lastName: string, current: string, nameTouched: boolean): string {
  if (nameTouched) return current;
  return [firstName, middleName, lastName].map(s => s.trim()).filter(Boolean).join(' ');
}

/** Step 1 — Basic Information. Same checks AddModal's handleSubmit already ran on create. */
export function validateStep1(form: Pick<CreateUserPayload, 'fullName' | 'email'>): string | null {
  const trimmedName = form.fullName.trim();
  const rawEmail = form.email;
  if (!trimmedName || !rawEmail) return 'Name and email required.';
  if (!NAME_PATTERN.test(trimmedName)) return "Full name can only contain letters, spaces, hyphens, and apostrophes - no emojis or symbols.";
  // Checked against the raw value, not trimmed — a leading/trailing space is rejected outright.
  if (rawEmail !== rawEmail.trim()) return 'Email must not have leading or trailing spaces.';
  if (!EMAIL_PATTERN.test(rawEmail)) return 'Enter a valid email address with a proper domain (e.g. name@company.com).';
  return null;
}

/** Step 2 — Employment & Job Details. Same required-field rules AddModal already enforced. */
export function validateStep2(form: Pick<CreateUserPayload, 'departmentId' | 'designationId' | 'locationId' | 'joiningDate'>): string | null {
  if (!form.locationId) return 'Location is required - Leave & Holidays depends on it.';
  if (!form.departmentId) return 'Department is required.';
  if (!form.designationId) return 'Designation is required.';
  if (!form.joiningDate) return 'Joining Date is required.';
  return null;
}

/**
 * Step 3 — Work & Policy Configuration. `todayIso` is passed in (rather than computed here) so
 * this stays a pure, deterministic function independent of the system clock/timezone — the
 * caller supplies it via businessTodayIsoDate(), exactly as AddModal's todayIso() already does.
 */
export function validateStep3(
  form: Pick<CreateUserPayload, 'role' | 'managerId' | 'shiftId' | 'effectiveFrom' | 'weeklyOffPolicyId' | 'weeklyOffEffectiveFrom' | 'penalisationPolicyId' | 'penalisationEffectiveFrom'>,
  todayIso: string,
): string | null {
  if (!form.role) return 'Role is required.';
  if (form.role !== 'SUPER_ADMIN' && !form.managerId) return 'Reporting Manager is required for this role.';
  if (form.shiftId && (!form.effectiveFrom || form.effectiveFrom < todayIso)) {
    return 'Shift Effective From is required and cannot be in the past.';
  }
  if (form.weeklyOffPolicyId && (!form.weeklyOffEffectiveFrom || form.weeklyOffEffectiveFrom < todayIso)) {
    return 'Weekly-Off Effective From is required and cannot be in the past.';
  }
  if (form.penalisationPolicyId && (!form.penalisationEffectiveFrom || form.penalisationEffectiveFrom < todayIso)) {
    return 'Attendance Policy Effective From is required and cannot be in the past.';
  }
  return null;
}

/**
 * Final payload construction — same trimming AddModal's handleSubmit already applied before
 * calling usersApi.create: fullName trimmed, employeeCode trimmed-or-undefined (so a
 * blank/whitespace-only Employee ID falls back to the backend's own auto-assignment).
 */
export function buildCreateUserPayload(form: CreateUserPayload): CreateUserPayload {
  const trimmedCode = (form.employeeCode ?? '').trim();
  return {
    ...form,
    fullName: form.fullName.trim(),
    employeeCode: trimmedCode || undefined,
  };
}
