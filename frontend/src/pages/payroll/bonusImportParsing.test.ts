import { describe, expect, it } from 'vitest';
import { findRunForDate, guessBonusColumnMapping, validateBonusRows, type EmployeeLookup, type RunWindow } from './bonusImportParsing';

describe('guessBonusColumnMapping', () => {
  it('matches common header spellings', () => {
    const mapping = guessBonusColumnMapping(['Employee Code', 'Bonus Amount', 'Payout Date', 'Reason']);
    expect(mapping).toEqual({ employeeCode: 0, amount: 1, payoutDate: 2, reason: 3 });
  });
});

describe('findRunForDate', () => {
  const runs: RunWindow[] = [
    { runId: 'r1', cycleStart: '2026-10-25', cycleEnd: '2026-11-24', status: 'DRAFT' },
    { runId: 'r2', cycleStart: '2026-11-25', cycleEnd: '2026-12-24', status: 'DRAFT' },
  ];

  it('finds the run whose cycle contains the date, inclusive of both ends', () => {
    expect(findRunForDate(runs, '2026-11-01')?.runId).toBe('r1');
    expect(findRunForDate(runs, '2026-10-25')?.runId).toBe('r1');
    expect(findRunForDate(runs, '2026-11-24')?.runId).toBe('r1');
    expect(findRunForDate(runs, '2026-11-25')?.runId).toBe('r2');
  });

  it('returns undefined when no run covers the date', () => {
    expect(findRunForDate(runs, '2027-01-01')).toBeUndefined();
  });
});

describe('validateBonusRows', () => {
  const mapping = { employeeCode: 0, amount: 1, payoutDate: 2, reason: 3 };
  const employees = new Map<string, EmployeeLookup>([['nf-001', { userId: 'u1', fullName: 'Asha Rao' }]]);
  const runs: RunWindow[] = [{ runId: 'r1', cycleStart: '2026-10-25', cycleEnd: '2026-11-24', status: 'DRAFT' }];

  it('resolves a valid row to its covering run', () => {
    const [row] = validateBonusRows([['NF-001', '10000', '2026-11-01', 'Diwali bonus']], mapping, employees, runs, 'Default reason');
    expect(row.error).toBeUndefined();
    expect(row.runId).toBe('r1');
    expect(row.amount).toBe(10000);
    expect(row.reason).toBe('Diwali bonus');
  });

  it('falls back to the default reason when the cell is blank', () => {
    const [row] = validateBonusRows([['NF-001', '10000', '2026-11-01', '']], mapping, employees, runs, 'Default reason');
    expect(row.reason).toBe('Default reason');
  });

  it('flags a date with no covering run instead of guessing or queueing it', () => {
    const [row] = validateBonusRows([['NF-001', '10000', '2027-01-01', 'x']], mapping, employees, runs, '');
    expect(row.error).toContain('No payroll run covers');
  });

  it('flags an unknown employee code', () => {
    const [row] = validateBonusRows([['NF-404', '10000', '2026-11-01', 'x']], mapping, employees, runs, '');
    expect(row.error).toContain('NF-404');
  });

  it('flags a non-positive amount', () => {
    expect(validateBonusRows([['NF-001', '0', '2026-11-01', 'x']], mapping, employees, runs, '')[0].error).toContain('amount');
  });

  it('flags a missing reason when there is no default either', () => {
    const [row] = validateBonusRows([['NF-001', '10000', '2026-11-01', '']], mapping, employees, runs, '');
    expect(row.error).toContain('Reason');
  });

  it('silently skips a fully blank trailing row', () => {
    const rows = validateBonusRows([['NF-001', '10000', '2026-11-01', 'x'], ['', '', '']], mapping, employees, runs, '');
    expect(rows).toHaveLength(1);
  });
});
