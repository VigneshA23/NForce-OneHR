import { describe, expect, it } from 'vitest';
import { compareGrants, describeAuthority, suggestCloneName, summarizeBulk } from './roleTools';

describe('suggestCloneName', () => {
  it('prefixes Copy of', () => {
    expect(suggestCloneName('Payroll Viewer', ['Manager'])).toBe('Copy of Payroll Viewer');
  });
  it('numbers the copy when the name is taken (case-insensitive)', () => {
    expect(suggestCloneName('Payroll Viewer', ['copy of payroll viewer'])).toBe('Copy of Payroll Viewer (2)');
    expect(suggestCloneName('Payroll Viewer', ['Copy of Payroll Viewer', 'Copy of Payroll Viewer (2)'])).toBe('Copy of Payroll Viewer (3)');
  });
  it('never exceeds the 50 character role-name limit', () => {
    const long = 'A'.repeat(48);
    expect(suggestCloneName(long, []).length).toBeLessThanOrEqual(50);
    expect(suggestCloneName(long, [suggestCloneName(long, [])]).length).toBeLessThanOrEqual(50);
  });
});

describe('compareGrants', () => {
  it('classifies each permission as same, differing scope, or on one side only', () => {
    const rows = compareGrants(
      [{ module: 'leave', action: 'VIEW', scope: 'ALL' }, { module: 'leave', action: 'APPROVE', scope: 'TEAM' }, { module: 'roles', action: 'VIEW', scope: 'ALL' }],
      [{ module: 'leave', action: 'VIEW', scope: 'ALL' }, { module: 'leave', action: 'APPROVE', scope: 'ALL' }, { module: 'payroll', action: 'VIEW', scope: 'ALL' }],
    );
    const by = (m: string, a: string) => rows.find((r) => r.module === m && r.action === a)!.status;
    expect(by('leave', 'VIEW')).toBe('same');
    expect(by('leave', 'APPROVE')).toBe('differs');
    expect(by('roles', 'VIEW')).toBe('onlyA');
    expect(by('payroll', 'VIEW')).toBe('onlyB');
  });
  it('is empty for two roles with no permissions', () => {
    expect(compareGrants([], [])).toEqual([]);
  });
});

describe('summarizeBulk', () => {
  it('counts changes, unchanged and failures', () => {
    const r = [
      { userId: '1', name: 'A', ok: true, message: 'Added' },
      { userId: '2', name: 'B', ok: true, message: 'Already held this role' },
      { userId: '3', name: 'C', ok: false, message: 'nope' },
    ];
    expect(summarizeBulk(r, false)).toBe('1 added · 1 unchanged · 1 failed');
    expect(summarizeBulk([{ userId: '1', name: 'A', ok: true, message: 'Removed' }], true)).toBe('1 removed');
  });
});

describe('describeAuthority', () => {
  it('renders module, action and scope in words', () => {
    expect(describeAuthority('employees:VIEW:ALL', { employees: 'Employees' }, { VIEW: 'View' })).toBe('Employees · View · Organization');
    expect(describeAuthority('x:Y:TEAM', {}, {})).toBe('x · Y · Team');
  });
});
