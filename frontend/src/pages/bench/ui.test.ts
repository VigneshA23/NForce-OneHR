import { describe, expect, it } from 'vitest';
import { todayLocal } from './ui';

describe('todayLocal', () => {
  it('formats the local calendar date, zero-padded', () => {
    expect(todayLocal(new Date(2026, 0, 5, 12, 0))).toBe('2026-01-05');
  });

  it('uses the local day, not the UTC day, just after local midnight', () => {
    // 00:30 local on 13 Oct — in any zone east of UTC the UTC date is still the 12th.
    expect(todayLocal(new Date(2026, 9, 13, 0, 30))).toBe('2026-10-13');
  });
});
