import { describe, expect, it } from 'vitest';
import { birthdayCountdownLabel, groupBirthdaysBySection } from './BirthdayWidget';
import type { BirthdayEntry } from '../api/birthdays';

function entry(overrides: Partial<BirthdayEntry>): BirthdayEntry {
  return {
    userId: 'u1',
    fullName: 'Test User',
    departmentName: 'Engineering',
    designationName: 'Software Engineer',
    birthdayMonth: 1,
    birthdayDay: 1,
    daysUntil: 0,
    today: true,
    ...overrides,
  };
}

describe('birthdayCountdownLabel', () => {
  it('labels a birthday today as "Today"', () => {
    expect(birthdayCountdownLabel(0)).toBe('Today');
  });

  it('labels a birthday tomorrow as "Tomorrow"', () => {
    expect(birthdayCountdownLabel(1)).toBe('Tomorrow');
  });

  it('labels any other day as "In N days"', () => {
    expect(birthdayCountdownLabel(5)).toBe('In 5 days');
    expect(birthdayCountdownLabel(7)).toBe('In 7 days');
  });
});

describe('groupBirthdaysBySection', () => {
  it('splits entries into today vs this week (everything from tomorrow onward)', () => {
    const entries = [
      entry({ userId: '1', today: true, daysUntil: 0 }),
      entry({ userId: '2', today: false, daysUntil: 1 }),
      entry({ userId: '3', today: false, daysUntil: 3 }),
      entry({ userId: '4', today: false, daysUntil: 7 }),
    ];
    const { today, thisWeek } = groupBirthdaysBySection(entries);
    expect(today.map(e => e.userId)).toEqual(['1']);
    expect(thisWeek.map(e => e.userId)).toEqual(['2', '3', '4']);
  });

  it('returns empty arrays for an empty input', () => {
    const { today, thisWeek } = groupBirthdaysBySection([]);
    expect(today).toEqual([]);
    expect(thisWeek).toEqual([]);
  });

  it('returns an empty "today" section when nobody has a birthday today', () => {
    const entries = [entry({ userId: '1', today: false, daysUntil: 2 })];
    const { today, thisWeek } = groupBirthdaysBySection(entries);
    expect(today).toEqual([]);
    expect(thisWeek.map(e => e.userId)).toEqual(['1']);
  });

  it('returns an empty "thisWeek" section when everyone\'s birthday is today', () => {
    const entries = [entry({ userId: '1', today: true, daysUntil: 0 })];
    const { thisWeek } = groupBirthdaysBySection(entries);
    expect(thisWeek).toEqual([]);
  });
});
