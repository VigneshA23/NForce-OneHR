import { describe, expect, it } from 'vitest';
import { buildCreateUserPayload, composeFullName, validateStep1, validateStep2, validateStep3 } from './wizardLogic';
import type { CreateUserPayload } from '../../api/employees';

function basePayload(overrides: Partial<CreateUserPayload> = {}): CreateUserPayload {
  return {
    fullName: 'Jane Smith',
    email: 'jane@nforceone.com',
    role: 'EMPLOYEE',
    departmentId: 'dept-1',
    designationId: 'desig-1',
    locationId: 'loc-1',
    joiningDate: '2026-11-10',
    managerId: 'mgr-1',
    ...overrides,
  };
}

describe('composeFullName', () => {
  it('joins first/middle/last while untouched', () => {
    expect(composeFullName('Jane', 'Q', 'Smith', '', false)).toBe('Jane Q Smith');
  });

  it('skips blank name parts without extra spaces', () => {
    expect(composeFullName('Jane', '', 'Smith', '', false)).toBe('Jane Smith');
  });

  it('trims each part before joining', () => {
    expect(composeFullName(' Jane ', '  ', ' Smith ', '', false)).toBe('Jane Smith');
  });

  it('never overwrites a manually-edited Full Name once touched', () => {
    expect(composeFullName('Jane', '', 'Smith', 'Something Else', true)).toBe('Something Else');
  });

  it('returns empty string when all name parts are blank and untouched', () => {
    expect(composeFullName('', '', '', 'stale', false)).toBe('');
  });
});

describe('validateStep1', () => {
  it('requires both name and email', () => {
    expect(validateStep1({ fullName: '', email: '' })).toBe('Name and email required.');
    expect(validateStep1({ fullName: 'Jane', email: '' })).toBe('Name and email required.');
  });

  it('rejects a name with digits or symbols', () => {
    expect(validateStep1({ fullName: 'Jane123', email: 'jane@nforceone.com' }))
      .toBe("Full name can only contain letters, spaces, hyphens, and apostrophes - no emojis or symbols.");
  });

  it('accepts hyphenated and apostrophe names', () => {
    expect(validateStep1({ fullName: "Anne-Marie O'Brien", email: 'jane@nforceone.com' })).toBeNull();
  });

  it('rejects an email with leading/trailing spaces without trimming it away', () => {
    expect(validateStep1({ fullName: 'Jane Smith', email: ' jane@nforceone.com' }))
      .toBe('Email must not have leading or trailing spaces.');
  });

  it('rejects a malformed email domain', () => {
    expect(validateStep1({ fullName: 'Jane Smith', email: 'jane@99999999999' }))
      .toBe('Enter a valid email address with a proper domain (e.g. name@company.com).');
  });

  it('passes for a valid name and email', () => {
    expect(validateStep1({ fullName: 'Jane Smith', email: 'jane@nforceone.com' })).toBeNull();
  });
});

describe('validateStep2', () => {
  const valid = { departmentId: 'd1', designationId: 'des1', locationId: 'l1', joiningDate: '2026-11-10' };

  it('requires location, department, designation, and joining date', () => {
    expect(validateStep2({ ...valid, locationId: undefined })).toBe('Location is required - Leave & Holidays depends on it.');
    expect(validateStep2({ ...valid, departmentId: undefined })).toBe('Department is required.');
    expect(validateStep2({ ...valid, designationId: undefined })).toBe('Designation is required.');
    expect(validateStep2({ ...valid, joiningDate: '' })).toBe('Joining Date is required.');
  });

  it('passes when all required fields are present', () => {
    expect(validateStep2(valid)).toBeNull();
  });
});

describe('validateStep3', () => {
  const today = '2026-11-09';
  const valid = { role: 'EMPLOYEE', managerId: 'mgr-1', shiftId: undefined, effectiveFrom: undefined, weeklyOffPolicyId: undefined, weeklyOffEffectiveFrom: undefined, penalisationPolicyId: undefined, penalisationEffectiveFrom: undefined };

  it('requires a role', () => {
    expect(validateStep3({ ...valid, role: '' }, today)).toBe('Role is required.');
  });

  it('requires a reporting manager unless role is SUPER_ADMIN', () => {
    expect(validateStep3({ ...valid, managerId: undefined }, today)).toBe('Reporting Manager is required for this role.');
    expect(validateStep3({ ...valid, role: 'SUPER_ADMIN', managerId: undefined }, today)).toBeNull();
  });

  it('requires Shift Effective From only when a shift is selected, and rejects a past date', () => {
    expect(validateStep3({ ...valid, shiftId: 'shift-1', effectiveFrom: undefined }, today)).toBe('Shift Effective From is required and cannot be in the past.');
    expect(validateStep3({ ...valid, shiftId: 'shift-1', effectiveFrom: '2026-11-08' }, today)).toBe('Shift Effective From is required and cannot be in the past.');
    expect(validateStep3({ ...valid, shiftId: 'shift-1', effectiveFrom: today }, today)).toBeNull();
  });

  it('requires Weekly-Off Effective From only when a weekly-off policy is selected, and rejects a past date', () => {
    expect(validateStep3({ ...valid, weeklyOffPolicyId: 'wo-1', weeklyOffEffectiveFrom: undefined }, today)).toBe('Weekly-Off Effective From is required and cannot be in the past.');
    expect(validateStep3({ ...valid, weeklyOffPolicyId: 'wo-1', weeklyOffEffectiveFrom: '2026-12-01' }, today)).toBeNull();
  });

  it('requires Attendance Policy Effective From only when a penalisation policy is selected, and rejects a past date', () => {
    expect(validateStep3({ ...valid, penalisationPolicyId: 'pp-1', penalisationEffectiveFrom: undefined }, today)).toBe('Attendance Policy Effective From is required and cannot be in the past.');
    expect(validateStep3({ ...valid, penalisationPolicyId: 'pp-1', penalisationEffectiveFrom: today }, today)).toBeNull();
  });

  it('passes with no shift, weekly-off, or penalisation policy selected', () => {
    expect(validateStep3(valid, today)).toBeNull();
  });
});

describe('buildCreateUserPayload', () => {
  it('trims fullName and a whitespace-only employeeCode falls back to undefined', () => {
    const payload = buildCreateUserPayload(basePayload({ fullName: '  Jane Smith  ', employeeCode: '   ' }));
    expect(payload.fullName).toBe('Jane Smith');
    expect(payload.employeeCode).toBeUndefined();
  });

  it('keeps a real employeeCode trimmed but intact', () => {
    const payload = basePayload({ employeeCode: ' NF-20260007 ' });
    expect(buildCreateUserPayload(payload).employeeCode).toBe('NF-20260007');
  });

  it('carries the new Step 1/Step 3 optional fields through unchanged', () => {
    const payload = basePayload({
      firstName: 'Jane', lastName: 'Smith',
      weeklyOffPolicyId: 'wo-1', weeklyOffEffectiveFrom: '2026-12-01',
      penalisationPolicyId: 'pp-1', penalisationEffectiveFrom: '2026-12-01',
    });
    const built = buildCreateUserPayload(payload);
    expect(built.firstName).toBe('Jane');
    expect(built.lastName).toBe('Smith');
    expect(built.weeklyOffPolicyId).toBe('wo-1');
    expect(built.penalisationPolicyId).toBe('pp-1');
  });
});
