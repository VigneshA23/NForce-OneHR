/**
 * Leave-balance day counts come from the backend as `BigDecimal` (Postgres `NUMERIC(5,2)` as of
 * V197 — see `db/migration/V197__widen_leave_day_precision_to_quarter_day.sql`), so any single
 * value handed to a component (`remainingDays`, `totalDays`, `usedDays`) is already exact and
 * safe to render as-is via `Number(...)`.
 *
 * The moment a component *derives* a new day count client-side — summing several balances'
 * `remainingDays`, or subtracting `available` from `total` to get "Consumed/Reserved" — plain
 * IEEE-754 arithmetic can reintroduce the exact visual noise the backend fix just eliminated
 * (e.g. `15 - 13.7` rendering as `1.3000000000000007`). `roundDays` strips that noise back out:
 * every real value here has at most 2 decimal digits of meaning, so rounding to 2 decimals after
 * the arithmetic recovers the exact mathematical result with no loss.
 */
import type { LeaveBalance } from '../api/leave';

export function roundDays(days: number): number {
  return Math.round(days * 100) / 100;
}

/**
 * A regular paid, per-leave-year balance with a quota — what "days left" totals add up. Unpaid
 * (unlimited) and per-occurrence (Maternity) rows are not a yearly allowance and are left out (V310).
 */
export function hasYearlyQuota(b: LeaveBalance): boolean {
  return !b.unlimited && (b.entitlementBasis ?? 'ANNUAL') === 'ANNUAL' && yearlyQuota(b) > 0;
}

/**
 * This year's entitlement plus any days carried forward from last year. For a year that hasn't
 * started (`expected`), nothing is credited yet, so it is the year's expected quota.
 */
export function yearlyQuota(b: LeaveBalance): number {
  if (b.expected) return Number(b.annualQuotaDays ?? 0) + Number(b.carriedForwardDays ?? 0);
  return Number(b.totalDays ?? 0) + Number(b.carriedForwardDays ?? 0);
}

/**
 * The leave years whose balances can be viewed (YE-04): the current year, plus the next one from
 * 1 November so employees planning January leave can see it. Same calendar leave year as the backend.
 */
export function viewableBalanceYears(today: Date = new Date()): number[] {
  const year = today.getFullYear();
  return today.getMonth() >= 10 ? [year, year + 1] : [year];
}

/**
 * The days of an inclusive ISO date range in each calendar (leave) year — the same split the backend
 * charges a cross-year request by (LeaveBalanceService#daysByYear). A half day is always one date.
 */
export function daysByYear(startIso: string, endIso: string, halfDay: boolean): Map<number, number> {
  const out = new Map<number, number>();
  if (!startIso || !endIso) return out;
  const startYear = Number(startIso.slice(0, 4));
  if (halfDay) {
    out.set(startYear, 0.5);
    return out;
  }
  const endYear = Number(endIso.slice(0, 4));
  const dayMs = 86400000;
  const utc = (iso: string) => Date.UTC(Number(iso.slice(0, 4)), Number(iso.slice(5, 7)) - 1, Number(iso.slice(8, 10)));
  const start = utc(startIso);
  const end = utc(endIso);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return out;
  for (let y = startYear; y <= endYear; y++) {
    const from = Math.max(start, Date.UTC(y, 0, 1));
    const to = Math.min(end, Date.UTC(y, 11, 31));
    if (to >= from) out.set(y, Math.round((to - from) / dayMs) + 1);
  }
  return out;
}
