import { describe, expect, it } from 'vitest';
import { roundDays } from './leaveDays';

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
