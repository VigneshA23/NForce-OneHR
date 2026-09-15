import { describe, expect, it } from 'vitest';
import { fullDayTargetMinutesFor } from './AttendancePage';
import type { AttendanceConfig } from '../api/attendance';

function baseConfig(overrides: Partial<AttendanceConfig>): AttendanceConfig {
  return {
    shiftName: 'Day Shift',
    shiftStart: '09:00:00',
    shiftEnd: '18:00:00',
    lateGraceMinutes: 10,
    halfDayMaxHours: 4,
    weeklyOffDays: ['SATURDAY', 'SUNDAY'],
    ...overrides,
  };
}

describe('fullDayTargetMinutesFor', () => {
  it('returns null for a null config', () => {
    expect(fullDayTargetMinutesFor(null)).toBeNull();
  });

  it('computes the shift duration in minutes for a normal same-day shift', () => {
    expect(fullDayTargetMinutesFor(baseConfig({}))).toBe(9 * 60);
  });

  it('wraps past midnight for an overnight shift', () => {
    const config = baseConfig({ shiftStart: '20:30:00', shiftEnd: '05:30:00' });
    expect(fullDayTargetMinutesFor(config)).toBe(9 * 60);
  });

  it('returns null for a no-shift-assigned employee (both fields null)', () => {
    expect(fullDayTargetMinutesFor(baseConfig({ shiftStart: null, shiftEnd: null }))).toBeNull();
  });

  it('returns null when shiftEnd is null (an assigned shift with no scheduled end)', () => {
    expect(fullDayTargetMinutesFor(baseConfig({ shiftEnd: null }))).toBeNull();
  });

  /**
   * Code-review fix: shiftStart and shiftEnd are independently nullable per AttendanceConfig's
   * own contract. A null shiftStart with a set shiftEnd must degrade to null like every other
   * incomplete-config case, never fall through to build a literal "...Tnull" string.
   */
  it('returns null when shiftStart is null but shiftEnd is set', () => {
    expect(fullDayTargetMinutesFor(baseConfig({ shiftStart: null }))).toBeNull();
  });
});
