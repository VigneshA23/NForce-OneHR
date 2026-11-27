/**
 * LV-05 year-end wizard logic, kept free of React so it can be unit tested.
 */
import type { YearEndRunDetail, YearEndRunSummary } from '../../api/leave';

/** HR's split of one line, as typed: the lapse it leaves, or why it isn't allowed (mirrors the server). */
export function checkSplit(closing: number, carry: string, encash: string): { carry: number; encash: number; lapse: number } | string {
  const c = carry.trim() === '' ? 0 : Number(carry);
  const e = encash.trim() === '' ? 0 : Number(encash);
  if (isNaN(c) || isNaN(e) || c < 0 || e < 0) return 'Days must be 0 or more.';
  if (Math.round((c + e) * 100) > Math.round(closing * 100)) {
    return `Carry and encash together can't exceed the ${closing} unused day(s).`;
  }
  return { carry: c, encash: e, lapse: Math.round((closing - c - e) * 100) / 100 };
}

/** The run the wizard shows for a year: the open preview, else the confirmed run, else the latest one. */
export function currentRun(runs: YearEndRunSummary[]): YearEndRunSummary | null {
  return runs.find(r => r.status === 'PREVIEW') ?? runs.find(r => r.status === 'CONFIRMED') ?? runs[0] ?? null;
}

/** Which wizard step a run is at: 1 preview (nothing adjusted yet), 2 adjusting, 3 confirmed; rolled back starts over. */
export function stepOf(run: YearEndRunSummary | null): 1 | 2 | 3 {
  if (!run || run.status === 'ROLLED_BACK') return 1;
  if (run.status === 'CONFIRMED') return 3;
  return run.adjustedLines > 0 ? 2 : 1;
}

/** Whether a confirmed run can still be rolled back at {@code now} (the server also checks usage and payroll). */
export function canRollBack(run: YearEndRunSummary, now: Date): boolean {
  return run.status === 'CONFIRMED' && !!run.rollbackUntil && new Date(run.rollbackUntil) > now;
}

/** Totals line for the run, e.g. "48 unused: 20 carried, 6 encashed (₹18,000), 22 lapse". */
export function totalsText(d: YearEndRunDetail): string {
  const r = d.run;
  const fmt = (n: number) => Number(n).toLocaleString('en-IN', { maximumFractionDigits: 2 });
  const money = r.encashAmount > 0 ? ` (₹${Number(r.encashAmount).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })})` : '';
  return `${fmt(r.closingDays)} unused day(s): ${fmt(r.carryDays)} carried, ${fmt(r.encashDays)} encashed${money}, ${fmt(r.lapseDays)} lapse`;
}
