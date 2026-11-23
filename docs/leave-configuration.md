# Leave configuration (V309)

Leave types, entitlements, carry-forward and balance adjustments live in **Organization Masters /
Organization Structure → Leave** (the `leave_configuration` permission). There is no separate page.

## Model

| Leave kind | Entitlement | Where the balance lives |
|---|---|---|
| Regular paid (`PAID`, basis `ANNUAL`) | Days per leave year, default 15 | `leave_balances`, one row per employee, type and year |
| Per occurrence (`PAID`, basis `PER_OCCURRENCE`, e.g. Maternity) | Days per eligible occurrence, default 182 | `leave_occurrences` |
| Unpaid (`UNPAID`) | Unlimited | none, nothing is created or deducted |

- Every paid type has its **own** balance. The old Annual/Sick/Casual shared balance is gone.
- Entitlements are rows in `leave_entitlement_policies` (`effective_year`, `days`). A year uses the latest
  policy with `effective_year <= year`. Changing an entitlement never rewrites existing balances unless
  "update existing balances" is ticked. Balance rows are created on first use from the policy in force,
  which covers new hires and leave types added later.
- **Leave year**: January–December, defined only in `LeaveBalanceService` (`leaveYearOf`, `yearStart`,
  `yearEnd`). A request that spans two years is checked and deducted per year.
- **Maternity**: the first approved request opens an occurrence with the entitlement and a window
  (`occurrence_window_months`, default 12). Every request starting inside that window draws on the same
  days, so splitting leave into several requests never yields a fresh entitlement. Maternity is counted in
  calendar days and may span weekly offs and holidays.
- **Carry-forward bucket** on each balance row (`carried_forward_*`). Carried days only cover leave dated
  on or before `carried_forward_expires_on`. They are used first or last according to the type's
  `carry_forward_priority`.
- **Pending requests** reserve days in each leave year they touch. Approval deducts the days.

## Year-end processing

`LeaveYearEndService` runs nightly (`app.leave.year-end.cron`, default 00:30 in the business zone). HR can
also run it from Carry-Forward Settings → Run now. For the leave year that just ended:

1. For every active employee and regular paid type, it computes unused days: current-year days left, plus
   carried-forward days still valid at year end, minus pending leave still reserved in that year.
2. When carry-forward is enabled, it carries `min(unused, max days)` into next year's carried-forward bucket,
   with expiry N months into the new year. Everything else lapses.
3. It records a row in `leave_carry_forward_transfers`. `UNIQUE (employee, type, from_year)` makes duplicate
   transfers impossible, so re-runs are no-ops.
4. Buckets past their expiry date have their unused days marked expired. Days still claimed by pending
   leave dated before expiry are kept.

Each employee is processed in its own transaction: a failure is counted and logged, and the next run picks
it up. If a request pending at year-end is rejected later, `refreshTransfers` raises that transfer, up to
the cap and only while the bucket hasn't expired. A transfer is never lowered.

## Migration and reset (production)

V309 is additive and idempotent, and runs in one transaction:

- It snapshots `leave_balances` into `leave_balances_pre_v306` before changing anything. The app never
  reads that table; it exists for rollback and reference.
- It seeds policies (15 days for every paid type, 182 for Maternity). It adds a `MATERNITY` type when no
  per-occurrence type exists; a paid type whose name starts with "Maternity" is converted instead.
- **It resets the current leave year** (Asia/Kolkata) for every employee and regular paid type:
  - entitlement = policy days;
  - used = that type's own approved leave days in the year, plus active attendance-penalty deductions of
    that type;
  - carried-forward = 0.

  The old consolidated Annual/Sick/Casual figures are discarded on purpose. Late-arrival half-day Casual
  deductions (`LatePenaltyService`) were never recorded per request and are not rebuilt. HR can correct any
  balance afterwards under Leave Entitlements → Employee balances → Adjust; every adjustment is audited
  with the previous value, new value, reason and administrator.
- It deletes current- and future-year balance rows of unpaid and per-occurrence types, which were never
  meaningful. Past-year rows and every leave request (type, dates, status, history) are untouched.
- It adds `leave_configuration` (VIEW / CONFIGURE / EDIT) under Organization → Organization Structure, held
  by HR Admin and Super Admin. Any other role that held `leave:CONFIGURE` gets VIEW + CONFIGURE (the same
  power it had). `leave:CONFIGURE` is then retired; leave application and approval permissions are unchanged.

Rollback: restore `leave_balances` from `leave_balances_pre_v306`, drop the three new tables and the new
columns, and re-register `leave:CONFIGURE`.

## Audit actions

`LEAVE_TYPE_CREATED`, `LEAVE_TYPE_UPDATED`, `LEAVE_TYPE_DEACTIVATED`, `LEAVE_TYPE_REACTIVATED`,
`LEAVE_TYPE_DELETED`, `LEAVE_ENTITLEMENT_CHANGED`, `LEAVE_CARRY_FORWARD_CONFIG_CHANGED`,
`LEAVE_YEAR_END_PROCESSED`, `LEAVE_CARRY_FORWARD_TOPPED_UP`, `LEAVE_CARRY_FORWARD_EXPIRED`,
`LEAVE_BALANCE_ADJUSTED`.
