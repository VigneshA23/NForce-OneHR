import { describe, expect, it } from 'vitest';
import { getAnniversaryYears, hasShownAnniversaryThisYear, markAnniversaryShown } from './workAnniversary';

// Minimal in-memory fake — this project's tests run in a plain-Node vitest environment with no
// `window`, so a real Storage isn't available (see themePreference.test.ts's fakeStorage).
function fakeStorage(): Pick<Storage, 'getItem' | 'setItem'> {
  const data = new Map<string, string>();
  return {
    getItem: (k) => data.get(k) ?? null,
    setItem: (k, v) => { data.set(k, v); },
  };
}

describe('getAnniversaryYears', () => {
  it('returns the completed years when today matches the joining month/day', () => {
    expect(getAnniversaryYears('2021-03-15', new Date('2026-03-15T09:00:00'))).toBe(5);
  });

  it('returns null when today is not the anniversary date', () => {
    expect(getAnniversaryYears('2021-03-15', new Date('2026-03-16T09:00:00'))).toBeNull();
    expect(getAnniversaryYears('2021-03-15', new Date('2026-04-15T09:00:00'))).toBeNull();
  });

  it('returns null on the join date itself (0 years is not an anniversary yet)', () => {
    expect(getAnniversaryYears('2026-03-15', new Date('2026-03-15T09:00:00'))).toBeNull();
  });

  it('returns null for missing or unparseable dates instead of throwing', () => {
    expect(getAnniversaryYears(null, new Date())).toBeNull();
    expect(getAnniversaryYears(undefined, new Date())).toBeNull();
    expect(getAnniversaryYears('', new Date())).toBeNull();
    expect(getAnniversaryYears('not-a-date', new Date())).toBeNull();
  });

  it('observes a Feb 29 join date on Feb 28 in a non-leap year', () => {
    expect(getAnniversaryYears('2020-02-29', new Date('2026-02-28T09:00:00'))).toBe(6);
    // ...but not on any other February day, and not doubly-triggered on Mar 1 either.
    expect(getAnniversaryYears('2020-02-29', new Date('2026-02-27T09:00:00'))).toBeNull();
    expect(getAnniversaryYears('2020-02-29', new Date('2026-03-01T09:00:00'))).toBeNull();
  });

  it('fires normally on Feb 29 in an actual leap year', () => {
    expect(getAnniversaryYears('2020-02-29', new Date('2028-02-29T09:00:00'))).toBe(8);
  });
});

describe('hasShownAnniversaryThisYear / markAnniversaryShown', () => {
  it('is false until marked, then true for that same year only', () => {
    const storage = fakeStorage();
    expect(hasShownAnniversaryThisYear(storage, 'a@example.com', 2026)).toBe(false);
    markAnniversaryShown(storage, 'a@example.com', 2026);
    expect(hasShownAnniversaryThisYear(storage, 'a@example.com', 2026)).toBe(true);
    expect(hasShownAnniversaryThisYear(storage, 'a@example.com', 2027)).toBe(false);
  });

  it('tracks each email independently', () => {
    const storage = fakeStorage();
    markAnniversaryShown(storage, 'a@example.com', 2026);
    expect(hasShownAnniversaryThisYear(storage, 'b@example.com', 2026)).toBe(false);
  });
});
