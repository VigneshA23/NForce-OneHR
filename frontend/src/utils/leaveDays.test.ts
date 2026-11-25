import { describe, expect, it } from 'vitest';
import { roundDays, daysByYear, viewableBalanceYears, yearlyQuota, yearBreakdownLabel } from './leaveDays';

describe('roundDays', () => {
  it('strips IEEE-754 noise from a subtraction of two exact 2-decimal values', () => {
    expect(roundDays(15 - 13.7)).toBe(1.3);
  });

  it('strips IEEE-754 noise from summing several 2-decimal values', () => {
    expect(roundDays(13.75 + 0.5 + 0)).toBe(14.25);
  });

  it('leaves an already-exact value unchanged', () => {
    expect(roundDays(14.75)).toBe(14.75);
    expect(roundDays(0)).toBe(0);
  });
});

describe('daysByYear (YE-03/YE-04)', () => {
  it('keeps a request inside one year whole', () => {
    expect([...daysByYear('2026-12-01', '2026-12-04', false)]).toEqual([[2026, 4]]);
  });
  it('splits a request over New Year by date', () => {
    expect([...daysByYear('2026-12-30', '2027-01-02', false)]).toEqual([[2026, 2], [2027, 2]]);
  });
  it('charges a half day to the year of its single date', () => {
    expect([...daysByYear('2027-01-01', '2027-01-01', true)]).toEqual([[2027, 0.5]]);
  });
  it('returns nothing for an incomplete or reversed range', () => {
    expect(daysByYear('', '2027-01-01', false).size).toBe(0);
    expect(daysByYear('2027-01-05', '2027-01-01', false).size).toBe(0);
  });
});

describe('viewableBalanceYears', () => {
  it('is only the current year before November', () => {
    expect(viewableBalanceYears(new Date(2026, 9, 31))).toEqual([2026]);
  });
  it('adds the next year from 1 November', () => {
    expect(viewableBalanceYears(new Date(2026, 10, 1))).toEqual([2026, 2027]);
    expect(viewableBalanceYears(new Date(2026, 11, 31))).toEqual([2026, 2027]);
  });
});

describe('yearlyQuota of an expected (not yet started) year', () => {
  it('is the expected quota, not the 0 credited so far', () => {
    expect(yearlyQuota({ leaveTypeCode: 'PAID', leaveTypeName: 'Paid Leave', year: 2027, expected: true,
      totalDays: 0, usedDays: 0, remainingDays: 0, annualQuotaDays: 15 })).toBe(15);
  });
});

describe('yearBreakdownLabel', () => {
  it('labels each year of a cross-year request', () => {
    expect(yearBreakdownLabel('2026-12-29', '2027-01-01', false)).toBe('2026: 3d · 2027: 1d');
  });
  it('is null for a single-year request', () => {
    expect(yearBreakdownLabel('2026-12-01', '2026-12-04', false)).toBeNull();
    expect(yearBreakdownLabel(null, null, false)).toBeNull();
  });
});
