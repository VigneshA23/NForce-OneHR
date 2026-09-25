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
export function roundDays(days: number): number {
  return Math.round(days * 100) / 100;
}
