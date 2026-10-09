import { describe, expect, it } from 'vitest';
import { fullDayTargetMinutesFor, filterByAttendanceDate } from './AttendancePage';
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

describe('filterByAttendanceDate', () => {
  const rows = [
    { id: '1', attendanceDate: '2026-09-17' },
    { id: '2', attendanceDate: '2026-09-09' },
    { id: '3', attendanceDate: '2026-09-16' },
    { id: '4', attendanceDate: '2026-09-09' },
    { id: '5', attendanceDate: '2026-09-11' },
  ];

  it('returns only rows matching the picked date, never a nearby date', () => {
    // ONEHR bug: selecting 09-09-2026 must never surface 09-17/09-16/09-11 rows alongside it.
    const result = filterByAttendanceDate(rows, '2026-09-09');
    expect(result.map(r => r.id)).toEqual(['2', '4']);
    expect(result.every(r => r.attendanceDate === '2026-09-09')).toBe(true);
  });

  it('returns every row when no date is picked (empty string)', () => {
    expect(filterByAttendanceDate(rows, '')).toEqual(rows);
  });

  it('returns an empty array for a date with no matching rows', () => {
    expect(filterByAttendanceDate(rows, '2026-01-01')).toEqual([]);
  });

  it('handles an empty input list', () => {
    expect(filterByAttendanceDate([], '2026-09-09')).toEqual([]);
  });
});
