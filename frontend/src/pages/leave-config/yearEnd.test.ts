import { describe, expect, it } from 'vitest';
import type { YearEndRunDetail, YearEndRunSummary } from '../../api/leave';
import { canRollBack, checkSplit, currentRun, stepOf, totalsText } from './yearEnd';

const run = (over: Partial<YearEndRunSummary>): YearEndRunSummary => ({
  id: 'r', fromYear: 2025, status: 'PREVIEW', createdByName: null, createdAt: '2026-01-05T10:00:00', confirmedByName: null,
  confirmedAt: null, rollbackUntil: null, rolledBackByName: null, rolledBackAt: null, lines: 2, adjustedLines: 0, closingDays: 10,
  carryDays: 5, encashDays: 3, lapseDays: 2, encashAmount: 3000, ...over,
});

describe('year-end wizard (LV-05)', () => {
  it('keeps carry + encash within the unused days and works out the lapse', () => {
    expect(checkSplit(10, '5', '3')).toEqual({ carry: 5, encash: 3, lapse: 2 });
    expect(checkSplit(7.33, '', '')).toEqual({ carry: 0, encash: 0, lapse: 7.33 });
    expect(checkSplit(10, '8', '3')).toBe("Carry and encash together can't exceed the 10 unused day(s).");
    expect(checkSplit(10, '-1', '0')).toBe('Days must be 0 or more.');
  });

  it('shows the open preview first, then the confirmed run, then the latest', () => {
    const confirmed = run({ id: 'c', status: 'CONFIRMED' }), preview = run({ id: 'p' }), rolled = run({ id: 'x', status: 'ROLLED_BACK' });
    expect(currentRun([rolled, confirmed, preview])?.id).toBe('p');
    expect(currentRun([rolled, confirmed])?.id).toBe('c');
    expect(currentRun([rolled])?.id).toBe('x');
    expect(currentRun([])).toBeNull();
  });

  it('knows the step and whether a rollback is still possible', () => {
    expect(stepOf(null)).toBe(1);
    expect(stepOf(run({ adjustedLines: 1 }))).toBe(2);
    expect(stepOf(run({ status: 'CONFIRMED' }))).toBe(3);
    expect(stepOf(run({ status: 'ROLLED_BACK' }))).toBe(1);
    const now = new Date('2026-01-08T00:00:00');
    expect(canRollBack(run({ status: 'CONFIRMED', rollbackUntil: '2026-01-12T10:00:00' }), now)).toBe(true);
    expect(canRollBack(run({ status: 'CONFIRMED', rollbackUntil: '2026-01-07T10:00:00' }), now)).toBe(false);
    expect(canRollBack(run({ status: 'PREVIEW', rollbackUntil: '2026-01-12T10:00:00' }), now)).toBe(false);
  });

  it('sums the run up', () => {
    const d: YearEndRunDetail = { run: run({}), lines: [], alreadyClosed: 0, leaversSkipped: 0 };
    expect(totalsText(d)).toBe('10 unused day(s): 5 carried, 3 encashed (₹3,000.00), 2 lapse');
  });
});
