import { describe, expect, it } from 'vitest';
import { isBirthdayToday, localDateKey, hasShownBirthdayToday, markBirthdayShown } from './birthday';

// Minimal in-memory fake — this project's tests run in a plain-Node vitest environment with no
// `window`, so a real Storage isn't available (see workAnniversary.test.ts's fakeStorage).
function fakeStorage(): Pick<Storage, 'getItem' | 'setItem'> {
  const data = new Map<string, string>();
  return {
    getItem: (k) => data.get(k) ?? null,
    setItem: (k, v) => { data.set(k, v); },
  };
}

describe('isBirthdayToday', () => {
  it('is true when today matches the month/day, regardless of birth year', () => {
    expect(isBirthdayToday('1994-03-15', new Date('2026-03-15T09:00:00'))).toBe(true);
  });

  it('is false when today is a different month or day', () => {
    expect(isBirthdayToday('1994-03-15', new Date('2026-03-16T09:00:00'))).toBe(false);
    expect(isBirthdayToday('1994-03-15', new Date('2026-04-15T09:00:00'))).toBe(false);
  });

  it('is false for missing or unparseable dates instead of throwing', () => {
    expect(isBirthdayToday(null, new Date())).toBe(false);
    expect(isBirthdayToday(undefined, new Date())).toBe(false);
    expect(isBirthdayToday('', new Date())).toBe(false);
    expect(isBirthdayToday('not-a-date', new Date())).toBe(false);
  });

  it('observes a Feb 29 birthday on Feb 28 in a non-leap year', () => {
    expect(isBirthdayToday('1996-02-29', new Date('2026-02-28T09:00:00'))).toBe(true);
    expect(isBirthdayToday('1996-02-29', new Date('2026-02-27T09:00:00'))).toBe(false);
    expect(isBirthdayToday('1996-02-29', new Date('2026-03-01T09:00:00'))).toBe(false);
  });

  it('fires normally on Feb 29 in an actual leap year', () => {
    expect(isBirthdayToday('1996-02-29', new Date('2028-02-29T09:00:00'))).toBe(true);
  });
});

describe('localDateKey', () => {
  it('formats as zero-padded YYYY-MM-DD', () => {
    expect(localDateKey(new Date('2026-03-05T09:00:00'))).toBe('2026-03-05');
    expect(localDateKey(new Date('2026-11-22T23:59:00'))).toBe('2026-11-22');
  });
});

describe('hasShownBirthdayToday / markBirthdayShown', () => {
  it('is false until marked, then true for that same day only', () => {
    const storage = fakeStorage();
    expect(hasShownBirthdayToday(storage, 'a@example.com', '2026-03-15')).toBe(false);
    markBirthdayShown(storage, 'a@example.com', '2026-03-15');
    expect(hasShownBirthdayToday(storage, 'a@example.com', '2026-03-15')).toBe(true);
    // A new day (including next year's same date) resets it.
    expect(hasShownBirthdayToday(storage, 'a@example.com', '2026-03-16')).toBe(false);
    expect(hasShownBirthdayToday(storage, 'a@example.com', '2027-03-15')).toBe(false);
  });

  it('tracks each email independently', () => {
    const storage = fakeStorage();
    markBirthdayShown(storage, 'a@example.com', '2026-03-15');
    expect(hasShownBirthdayToday(storage, 'b@example.com', '2026-03-15')).toBe(false);
  });
});
