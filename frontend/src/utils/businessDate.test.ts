import { afterEach, describe, expect, it, vi } from 'vitest';
import { businessTodayIsoDate } from './businessDate';

describe('businessTodayIsoDate', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('returns the current date when UTC and Asia/Kolkata agree on the calendar day', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-03-10T10:00:00Z')); // 15:30 IST — same calendar day as UTC

    expect(businessTodayIsoDate()).toBe('2026-03-10');
  });

  /**
   * Code-review fix: between ~18:30 and 23:59 UTC, Asia/Kolkata (UTC+5:30) has already rolled
   * over to the next calendar date. The Add User Effective From default must read the business
   * (Kolkata) date here, never the plain UTC one — otherwise it defaults to "yesterday" relative
   * to the backend's own business-date validation and gets rejected as "cannot be in the past".
   */
  it('returns the Kolkata date, not the UTC date, once Kolkata has already rolled over to the next day', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-03-10T19:00:00Z')); // 00:30 IST on 2026-03-11

    expect(businessTodayIsoDate()).toBe('2026-03-11');
  });

  it('stays on the UTC date just before the Kolkata rollover', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-03-10T18:00:00Z')); // 23:30 IST on 2026-03-10

    expect(businessTodayIsoDate()).toBe('2026-03-10');
  });
});
