# Leave configuration (V310, V314)

Leave types, who each type applies to, entitlements and crediting, carry-forward and balance adjustments
live in **Organization Masters / Organization Structure → Leave** (the `leave_configuration` permission).
There is no separate page.

## Paid and Unpaid leave (V314)

- There are two **built-in** types: **Paid Leave** (`system_key = PAID`) and **Unpaid Leave**
  (`system_key = UNPAID`). They can be renamed and restricted but never deleted, deactivated or
  reclassified. HR can still add more types, each paid or unpaid.
- V314 merged every other type into them: paid yearly types (Annual, Sick, Casual, …) into Paid Leave,
  unpaid types (LOP, …) into Unpaid Leave. Per-occurrence types and any type named Maternity were kept.
- `LatePenaltyService` (legacy late-arrival half-day) now debits the built-in Paid type instead of `CASUAL`.

## Crediting (V314)

Each regular paid type's yearly quota reaches the balance as monthly credits recorded in
`leave_accrual_credits` (one row per employee, type, year and month; the UNIQUE key makes crediting safe
to repeat). `leave_balances.total_days` now means **credited so far**.

| Setting | When | Joiners |
|---|---|---|
| `MONTHLY` | quota / 12 on the 1st of each month | the joining month is credited in full (no pro-rating) |
| `YEARLY` (all at once) | the whole quota on 1 January | the months left, counting the joining month, rounded to 0.5 |

- Month *m*'s share is `round2(q·m/12) − round2(q·(m−1)/12)`, so the year always adds up to the quota.
- The frequency is stored on the entitlement policy (`credit_frequency`), versioned by effective year.
  Switching mid-year needs nothing special: credited months stay credited; the rest follow the new setting.
- Credits stop after the month of `last_working_day`, for inactive users, for inactive types and for
  employees no longer eligible for the type. What was already credited stays.
- **Leave in advance**: requests and approvals may use the credits still to come this year, so
  `used_days` can exceed `total_days` (a negative balance, recovered by later credits). The displayed
  balance, the "use paid leave before unpaid" rule and penalty debits count credited days only.
  At exit, payroll's leave-encashment figure is negative when days were taken in advance.
- `LeaveAccrualService` runs nightly (`app.leave.accrual.cron`, default 00:15 in the business zone;
  in January it also finishes the previous year). HR can run it from Leave Entitlements → Run crediting
  now. Balances are also brought up to date whenever they are read.
- "Update existing balances" on an entitlement change re-credits already-credited months at the new rate
  and adds the difference (manual adjustments survive).
- Migration: every balance of the current and earlier years got 0-day `MIGRATION` rows for all 12
  months, so those years count as fully credited and nothing is added on top. Future-year rows restart
  at 0 credited. Paid Leave's policy is 15 days `YEARLY` up to the migration year and 15 days `MONTHLY`
  from the next year.

## Who a type applies to (V314)

`leave_type_eligibility_rules` (GENDER, DEPARTMENT, LOCATION, EMPLOYMENT_TYPE) and
`leave_type_employee_overrides` (INCLUDE / EXCLUDE). An employee is eligible unless excluded; otherwise
when included, or when they match every rule kind that has values (values of one kind are OR'ed). No rules
= everyone. An employee with no gender on file never matches a gender rule. Maternity was seeded as
Female only.

Ineligible employees don't see the type (`GET /api/leave/types/mine`), can't apply for it, get no balance
row or credits for it, and penalties can't debit it. Losing eligibility keeps the existing balance and
pending requests.

## Model

| Leave kind | Entitlement | Where the balance lives |
|---|---|---|
| Regular paid (`PAID`, basis `ANNUAL`) | Days per leave year, default 15, credited monthly or all at once | `leave_balances`, one row per employee, type and year; credits in `leave_accrual_credits` |
| Per occurrence (`PAID`, basis `PER_OCCURRENCE`, e.g. Maternity) | Days per eligible occurrence, default 182 | `leave_occurrences` |
| Unpaid (`UNPAID`) | Unlimited | none, nothing is created or deducted |

- Every paid type has its **own** balance. The old Annual/Sick/Casual shared balance is gone.
- Entitlements are rows in `leave_entitlement_policies` (`effective_year`, `days`, `credit_frequency`). A
  year uses the latest policy with `effective_year <= year`. Months not yet credited follow a change;
  credited months change only if "update existing balances" is ticked. Balance rows are created (0
  credited) on first use and credited at once with everything due, which covers new hires and leave
  types added later.
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

V310 is additive and idempotent, and runs in one transaction:

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

## Rollback (V314)

Restore `leave_types`, `leave_balances` and `leave_carry_forward_transfers` from the `*_pre_v314`
snapshots, repoint `leave_requests` by `original_leave_type_name`, then drop `leave_accrual_credits`,
`leave_type_eligibility_rules`, `leave_type_employee_overrides` and the new columns.

## Audit actions

`LEAVE_TYPE_CREATED`, `LEAVE_TYPE_UPDATED`, `LEAVE_TYPE_DEACTIVATED`, `LEAVE_TYPE_REACTIVATED`,
`LEAVE_TYPE_DELETED`, `LEAVE_ENTITLEMENT_CHANGED`, `LEAVE_CARRY_FORWARD_CONFIG_CHANGED`,
`LEAVE_YEAR_END_PROCESSED`, `LEAVE_CARRY_FORWARD_TOPPED_UP`, `LEAVE_CARRY_FORWARD_EXPIRED`,
`LEAVE_BALANCE_ADJUSTED`, `LEAVE_ACCRUAL_PROCESSED`, `LEAVE_TYPE_APPLICABILITY_CHANGED`.
