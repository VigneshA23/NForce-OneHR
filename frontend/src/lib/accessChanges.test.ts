import { describe, expect, it } from 'vitest';
import { actionLabel, changeReason, daysAgo, summarizeChange } from './accessChanges';

describe('access changes helpers', () => {
  it('daysAgo gives the calendar date N days back, across a month boundary', () => {
    expect(daysAgo(30, new Date(2026, 10, 20))).toBe('2026-10-21');
    expect(daysAgo(7, new Date(2026, 10, 3))).toBe('2026-10-27');
  });

  it('summarizes a permission change as added/removed counts', () => {
    const before = JSON.stringify({ role: 'R', permissions: ['a:VIEW:ALL', 'b:VIEW:ALL'] });
    const after = JSON.stringify({ role: 'R', permissions: ['a:VIEW:ALL', 'c:VIEW:ALL', 'd:VIEW:ALL'] });
    expect(summarizeChange('ROLE_PERMISSIONS_UPDATED', before, after)).toBe('2 added, 1 removed');
  });

  it('says which version a restore went back to', () => {
    const before = JSON.stringify({ permissions: ['a:VIEW:ALL', 'x:VIEW:ALL'] });
    const after = JSON.stringify({ permissions: ['a:VIEW:ALL'], restoredFromVersion: 2 });
    expect(summarizeChange('ROLE_RESTORED', before, after)).toBe('Restored to version 2 (0 added, 1 removed)');
  });

  it('summarizes role assignment changes by role code', () => {
    const before = JSON.stringify({ roles: ['EMPLOYEE'] });
    const after = JSON.stringify({ roles: ['EMPLOYEE', 'HR_ADMIN'] });
    expect(summarizeChange('ROLE_CHANGED', before, after)).toBe('+ HR_ADMIN');
  });

  it('shows field names (never values) for sensitive-data actions', () => {
    expect(summarizeChange('USER_SENSITIVE_DATA_VIEWED', null, JSON.stringify({ fields: ['bankAccountNumber', 'nationalId'] })))
      .toBe('Fields: bankAccountNumber, nationalId');
  });

  it('survives missing or malformed snapshots', () => {
    expect(summarizeChange('USER_CREATED', null, null)).toBe('');
    expect(summarizeChange('ROLE_CHANGED', 'not json', '{"roles":')).toBe('');
  });

  it('reads the reason, or a reviewer comment, from the after-snapshot', () => {
    expect(changeReason(JSON.stringify({ reason: ' Rolling back ' }))).toBe('Rolling back');
    expect(changeReason(JSON.stringify({ reviewComment: 'Wrong account' }))).toBe('Wrong account');
    expect(changeReason(JSON.stringify({ role: 'R' }))).toBe('');
    expect(changeReason(null)).toBe('');
  });

  it('labels known actions and falls back to a readable form for others', () => {
    expect(actionLabel('ROLE_RESTORED')).toBe('Role restored to a version');
    expect(actionLabel('SOMETHING_NEW')).toBe('Something new');
  });
});
