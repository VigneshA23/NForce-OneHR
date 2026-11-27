# Leave configuration (V310, V314, V321, V322, V323, V324, V325, V326, V327)

Leave types, leave plans (each type's days, crediting, carry-forward and who it applies to), who is on which
plan, balances and year-end live in **Organization Masters / Organization Structure → Leave** (the
`leave_configuration` permission). There is no separate page. Tabs: Leave Types · Leave Plans · Assignment ·
Balances · Year-End.

## Leave plans (V321, LV-01 / ONEHR-886)

A **leave plan** bundles leave types with their own rules, so different groups (e.g. "Full-time India",
"Interns") get different entitlements without code changes.

- **Leave types are a catalogue**: name, code, paid/unpaid, per-year or per-occurrence basis. Everything
  else — days, crediting frequency, carry-forward (cap, expiry, which bucket is used first) and "applies to"
  — is set per type **in a plan**. A type no plan includes can't be used by anyone.
- **Versions**: a plan's rules are effective-dated versions (`leave_plan_versions` →
  `leave_plan_type_rules`, `leave_plan_type_eligibility`, `leave_plan_type_overrides`). Saving rules creates a
  version from the chosen date and ends the version it starts inside the day before; a version starting that
  same day is replaced; later scheduled versions are kept. Rules can't take effect before 1 January of the
  current leave year (earlier years are closed). Saving unchanged rules creates nothing.
- **Which plan an employee is on** for a date (`LeavePlanCatalog.planOf`): an explicit **allocation**
  (`leave_plan_allocations`, effective-dated, never overlapping — a DB exclusion constraint), else the first
  matching **assignment rule** (the shared V318 rule engine, domain `LEAVE_PLAN`: department, location, shift,
  employment type, grade; re-evaluated when an employee's details change), else the **organization default**.
  Inactive plans are skipped at every step. So every employee has exactly one plan, and new joiners with no
  matching rule get the default.
- **Changing an employee's plan** (allocation added/changed/ended, rule outcome changed, plan deactivated,
  default changed) re-credits the months already credited from that day (`LeaveAccrualService.reconcile`):
  months under the old plan keep its shares, later months get the new plan's, and months whose new plan
  doesn't include the type are taken back. Losing "applies to" eligibility alone never takes credits back.
  V314-migrated balances (no per-month figures) are not re-credited by a plan switch.
- Safeguards: the org default can't be deactivated or deleted; a plan that was ever assigned (directly or by
  a rule) can't be deleted, only deactivated; clone copies the rules in effect today, not the assignments.
- The leave-year start month is stored per plan but only January is allowed for now (DB check).
- Migration: V321 seeds one org-default plan **"Default"** that reproduces the old per-type configuration
  exactly: one version per `leave_entitlement_policies.effective_year` (the first also covering all earlier
  dates), every leave type with the days/frequency in force that year, its carry-forward settings and its
  eligibility rules/overrides. Everyone is on it, so nothing changes on release day. The old
  `leave_entitlement_policies`, `leave_type_eligibility_rules`, `leave_type_employee_overrides` and the
  carry-forward columns of `leave_types` are no longer read; they stay for rollback.
- Endpoints: `/api/leave-config/plans`, `/plan-versions/{id}`, `/plan-allocations/*` (VIEW reads,
  CONFIGURE changes); rules via `/api/assignment-rules?domain=LEAVE_PLAN`, also guarded by
  `leave_configuration` (every rule change needs CONFIGURE, since the module has no CREATE/DELETE grants).

## Request rules per type (V322, LV-02 / ONEHR-887)

Each type in a plan also says how it may be requested (`leave_plan_type_rules`, plan editor → a type → More →
Request rules). Blank means no limit; the seeded Default plan has none, so nothing changes on release day.

| Rule | Meaning |
|---|---|
| Entitlement | **Fixed days** (the plan's days), **Unlimited** (paid, nothing checked or deducted; no carry-forward) or **Earned only** (nothing credited automatically, only what HR credits). Regular paid types only. Comp-Off is always earned-only: its days come from the comp-off ledger. |
| Min / max days per request | The request's day count. |
| Requests a month / a year | PENDING and APPROVED requests of the type in the month / year the request starts in. |
| Max days in a row | The request plus same-type leave directly before and after it, with only weekly offs and holidays between, chained. |
| Notice (days), only over N days | The request must be made at least N days before it starts, optionally only for requests longer than N days. Checked on submit and edit, not on approval. |
| Half days, by the hour, min hours | Half days are allowed unless switched off. Hourly leave is one day and less than a full day; it counts as hours ÷ `app.leave.hours-per-day` (default 8) days, to 2 decimals, and is stored as `duration_type = HOURLY` with `leave_hours`. |
| Attachment needed over N days, file types, max MB | The request needs a file when it is longer than N days (0 = always). File types are extensions (e.g. `pdf,jpg`); the size limit is 1–10 MB, 10 MB when not set. |
| Can't be taken next to | Leave types (`leave_plan_type_clubbing`) that may not end on the working day before this type starts or start on the working day after it ends. Checked from both sides: either type's rule blocks the pair. |

- Enforcement (`LeaveRuleValidator`): on submit, on edit and again on approval (everything except notice).
  The first broken rule is returned as a 400 naming the type and the limit, e.g. "Paid Leave over 1 day needs
  an attachment (PDF, max 1 MB)." A type with no rule in the employee's plan isn't checked here (eligibility
  already hides it).
- Working days for "in a row" and "next to" come from `LeaveCalendar`: the employee's Location holidays and
  the Weekly Off Policy governing each date (Saturday/Sunday when none is assigned).
- Attachments: one per request (`leave_request_attachments`, bytes in the DB, like Helpdesk). Submit and edit
  accept `multipart/form-data` with a JSON part `request` and a file part `attachment`; a new file replaces
  the old one. `GET /api/leave/requests/{id}/attachment` is open to the requester and to whoever may decide
  the request. My Requests and the Approval Center show the file name.
- The apply form shows the selected type's rules (`/api/leave/types/mine` returns them as `rules`), hides
  half day when it isn't allowed, offers "By the hour" when it is, and has the file input.
- The old balance-group idea from the story is not built: V314 already merged every paid type into Paid Leave
  with its own balance.

## Day count and the sandwich rule (V323, LV-07 / ONEHR-893)

A request may now span weekly offs and holidays (before LV-07 any such date was refused, so a Friday–Monday
leave had to be split). `LeaveDayCalculator` counts it:

- **Sandwich off (default)**: only working days count. Weekly offs come from the Weekly Off Policy governing each
  date (Saturday/Sunday when none is assigned), holidays from the employee's Location.
- **Sandwich on** (plan editor → a type → Request rules): the weekly offs / holidays *between* two leave days of
  the request count too. Options: holidays, weekly offs or both; only when the leave covers at least N working
  days. Non-working days at the start or end of the range are never counted.
- **Across separate requests** (option): the non-working days between this request and an adjacent PENDING or
  APPROVED request of the same type (Friday, then Monday) count too, and are charged to the **later-made**
  request, so they are never counted twice. Full-day requests only. If the earlier request is rejected, a
  pending later one is recounted (`LEAVE_REQUEST_RECALCULATED` in the audit log); an approved one keeps its
  count.
- A range with no working day is refused; a single non-working date keeps the old message naming the date.
  Half-day and hourly leave must fall on a working day. Per-occurrence leave (Maternity) still counts calendar
  days, and the sandwich rule doesn't apply to it.
- The dates charged are stored on the request (`leave_requests.day_counts`). Balances split a cross-year request
  per leave year, and apply carry-forward expiry, from those dates; requests made before V323 have none and
  every date in their range counted, as before. Payroll LOP and reports use `total_days`, which includes
  sandwiched days.
- The apply form asks `POST /api/leave/requests/preview` (`?editing=` for an edit) as the dates change and
  shows "4 days: 2 working + 2 sandwiched", which dates are sandwiched or not counted, and the first reason the
  request would be refused. My Requests shows "incl. N sandwiched".

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
- Days and frequency come from the employee's leave plan **month by month** (the plan and version in effect
  on the 1st, or on the joining date in the joining month). A run of months under one plan and quota is one
  YEARLY block; a part-year block (joiner, leaver, plan switch) is rounded to the nearest 0.5. Switching
  frequency mid-year needs nothing special: credited months stay credited; the rest follow the new setting.
- Credits stop after the month of `last_working_day`, for inactive users, for inactive types and for
  employees no longer eligible for the type. What was already credited stays.
- **Leave in advance**: requests and approvals may use the credits still to come this year, so
  `used_days` can exceed `total_days` (a negative balance, recovered by later credits). The displayed
  balance, the "use paid leave before unpaid" rule and penalty debits count credited days only.
  At exit, payroll's leave-encashment figure is negative when days were taken in advance.
- LV-03 adds quarterly and half-yearly crediting, end-of-period timing and pauses: see the next section.
- `LeaveAccrualService` runs nightly (`app.leave.accrual.cron`, default 00:15 in the business zone;
  in January it also finishes the previous year). HR can run it from Balances → Run crediting now. Balances are also brought up to date whenever they are read.
- "Re-credit months already credited" when saving plan rules re-credits those months at the new rate and
  adds the difference (manual adjustments survive). From 1 January it also resets V314-migrated balances.
- Migration: every balance of the current and earlier years got 0-day `MIGRATION` rows for all 12
  months, so those years count as fully credited and nothing is added on top. Future-year rows restart
  at 0 credited. Paid Leave's policy is 15 days `YEARLY` up to the migration year and 15 days `MONTHLY`
  from the next year.

## Accrual periods, timing and pauses (V326, LV-03 / ONEHR-889)

Per leave type in a plan (Leave Plans → a type's row and its **More** panel), for regular paid types with fixed days:

| Setting | Values | Effect |
|---|---|---|
| Frequency | Monthly, Quarterly, Half-yearly, All at once (yearly) | How many months' share is credited together: 1, 3 (Jan, Apr, Jul, Oct), 6 (Jan, Jul) or 12. |
| Timing | Start / end of the period | **Start**: on the period's first day (as before LV-03). **End**: once the period is over, i.e. the day after it ends. A leaver's last period is credited the day after the last working day. |
| Pause after loss of pay over N days | blank = never | The period's credit is withheld. |
| Pause after unpaid leave over N days | blank = never | Same, for approved leave of an Unpaid-classified type. |

- The ledger stays **one row per month** (`leave_accrual_credits`), so idempotency, re-crediting after plan
  changes, proration (LV-04) and exit settlements work unchanged. A quarter's three months are simply
  posted together on the quarter's credit date.
- Months credited together that don't make up a whole period (a joiner, a leaver, a plan switch) are rounded
  per the plan's proration rounding. Example: 10 days a year, quarterly, joined 10 February: Feb + Mar =
  0.84 + 0.83 = 1.67, rounded to 1.5.
- **Which period a pause looks at**: credited at the **end**, the period itself; credited at the **start**, the
  period before it (its own period hasn't happened yet). "Over N" means strictly more than N.
- **Loss of pay** is payroll's LOP calculation (`payroll_lop_calculations`, HR's override when set) for every
  pay cycle that **ends** inside that period. A cycle payroll hasn't calculated yet counts as 0, so with
  start-of-period crediting the previous period's LOP must be calculated before the credit date to take effect.
  **Unpaid leave** is approved Unpaid-type leave, counted by the dates it is charged for.
- A withheld month is recorded as a 0-day `PAUSED` row: it shows as "Paused" in the credit history, a re-run
  never credits it, and re-crediting after a plan change leaves it withheld. To give the days anyway, adjust
  the balance.
- **Dry run**: Balances → Crediting → **Preview** (any date) lists every credit a run on that date would post:
  employee, type, period, days, credit date, and Credit or Paused with the reason. Nothing is written
  (`GET /api/leave-config/accrual/preview?asOf=`, leave configuration VIEW). "Run crediting now" also reports
  how many months were paused.
- **Employees** see, per paid type: accrued to date (credited so far, plus carried forward), used, available,
  and the next credit's amount and date.
- Defaults (start of period, no pause) are exactly the behaviour before LV-03.
- **Not built**: the unified leave ledger (`leave_ledger_entries` with every movement and an employee statement)
  is LV-11. The per-credit ledger the story asks for is `leave_accrual_credits`.

## Proration and exits (V325, LV-04 / ONEHR-890)

Each plan says how a part-year entitlement is prorated (plan editor → "Joiners and leavers"):

- **By whole months** (default): the joining and exit months count in full. With a **cut-off day**, joining after
  it skips the joining month ("joining after the 15th counts from the next month") and leaving before it skips the
  exit month.
- **By calendar days**: the joining / exit month counts the share of its days served.
- **Rounding**: nearest half day (default), down or up to a half day, or none. All-at-once crediting rounds the
  part-year total; monthly crediting rounds only a partly-served month (full months stay exact). Rounding down
  never makes a month negative.
- Worked example: 15 days a year, joined 1 October, whole months → 3 × 1.25 = 3.75 → nearest 0.5 = **4**.
- The defaults (whole months, no cut-off, nearest half) are exactly how crediting worked before, so nothing
  changes until HR changes a plan. Changing a plan's proration re-credits this year's joining / exit months.
- A mid-year plan switch is still prorated month by month (each month follows its plan, see Leave plans).

**Exits.** For an employee with a last working day (entered on the employee record — there is no exit screen
yet), `LeaveExitService` recomputes the leave year of that day: credits after it are taken back and the exit
month is prorated, then per regular paid type it stores (`leave_exit_settlements`) what was earned, carried,
used, and the result — days to **encash**, or days taken beyond the entitlement to **recover** in the final
settlement. It runs nightly (`app.leave.exit.cron`, 00:45) for last working days within 60 days of today, and on
demand: Leave → Exits → Recompute, and payroll's exit-cycle proration (whose leave encashment days now come from
it instead of the plain balance). Payroll's Joiners & Exits shows "Leave to encash" / "Leave to recover" per
leaver. Clearing a last working day removes the settlement and restores the year's credits.

## Eligibility: marital status, grade, tenure, probation (V324, LV-06 / ONEHR-892)

Each type in a plan (plan editor → a type → More → Applies to) can now also be limited by:

- **Marital status** (as on the employee profile) and **grade** (the grade of the employee's designation). Like
  gender, department, location and employment type, these say *who* the type is for: an employee it isn't for
  doesn't see it on the apply form, and the backend answers "Paid Leave does not apply to you."
- **Minimum service (months)** and **not during probation** say *when*. They are checked on the leave's **start
  date**, not the day it is applied for. The type stays on the apply form with the date it becomes available
  (the start-date picker begins there), and a request starting earlier is refused with that date, e.g. "Paid
  Leave isn't available during probation: you can take it from 1 Apr 2027."
- Probation: before `confirmation_date`; with no confirmation date, up to `probation_end_date`; no dates = not on
  probation. Both dates come from the employee record (the app doesn't edit them).
- Crediting: a month counts once the minimum service is reached on its date. A type not allowed during probation
  gets **no credits while the employee is on probation**; the first crediting run after probation (nightly, or
  "Run crediting now") credits every due month, including the probation months — the remaining entitlement
  arrives on confirmation.
- Hand-picked "always include" employees bypass every condition; "always exclude" always wins. Balances, opening
  balances and penalty debits only look at *who* (a type held back by tenure/probation still has its balance).
- Rules follow the plan version in effect on the leave's start date, like every other plan rule.

## Who a type applies to (V314; per plan since V321)

A type is available only if the employee's plan includes it; then its "applies to" rules in that plan
(`leave_plan_type_eligibility`: GENDER, DEPARTMENT, LOCATION, EMPLOYMENT_TYPE; `leave_plan_type_overrides`:
INCLUDE / EXCLUDE) decide. An employee is eligible unless excluded; otherwise
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
- Entitlements are the type's rule in the employee's leave plan (see Leave plans). Months not yet credited
  follow a change; credited months change only if "re-credit" is ticked or the employee changes plan.
  Balance rows are created (0 credited) on first use and credited at once with everything due, which covers
  new hires and leave types added later.
- **Leave year**: January–December, defined only in `LeaveBalanceService` (`leaveYearOf`, `yearStart`,
  `yearEnd`). A request that spans two years is checked and deducted per year.
- **Maternity**: the first approved request opens an occurrence with the entitlement and a window
  (`occurrence_window_months`, default 12). Every request starting inside that window draws on the same
  days, so splitting leave into several requests never yields a fresh entitlement. Maternity is counted in
  calendar days and may span weekly offs and holidays.
- **Carry-forward bucket** on each balance row (`carried_forward_*`). Carried days only cover leave dated
  on or before `carried_forward_expires_on`. They are used first or last according to the plan rule's
  `carry_forward_priority`.
- **Pending requests** reserve days in each leave year they touch. Approval deducts the days.

## Year-end processing (V327, LV-05 / ONEHR-891)

**Behaviour change:** a leave year is no longer closed by the nightly job. HR closes it in Year-End as a
three-step wizard. Until then, last year's unused days stay in last year's balance and nothing is carried.
The nightly job (`app.leave.year-end.cron`, default 00:30) still applies carried-forward **expiries** and
**top-ups**; "Apply expiries now" runs it by hand.

Per regular paid type in a plan (Leave Plans → **More**):

| Setting | Effect |
|---|---|
| Carry-forward max days / max % | Carry at most this; with both, the lower. The % is of the unused days, rounded down to 0.01. |
| Expires after N months / or use by (day, month) | Carried days expire N months into the next year, or on that date of the next year. One or the other, not both. |
| Encash unused days, max days | Days beyond the carry-forward are paid, up to the max (blank = all). |
| Basis, divided by | Amount = days × monthly Basic (or Basic + DA) ÷ 30 (or 26). |

Order: **carry up to its cap → encash up to its cap → the rest lapses.**

1. **Preview** (`POST /api/leave-config/year-end/runs {fromYear}`). For every active employee and regular paid
   type, the unused days on 31 December are computed as before (days left + carried-forward still valid −
   pending leave reserved in the year) and split per the employee's plan on that day. Encashment is priced
   from the salary structure in effect on 31 December (the component named "Basic", plus "DA" / "Dearness
   Allowance" for Basic + DA, annual ÷ 12). Without one, the line says so and nothing is paid.
   Leavers (last working day in or before the year) are left out: their exit settlement (LV-04) covers them,
   so nothing is encashed twice. Balances already closed by the nightly job before LV-05 are left out too.
   A new preview replaces the previous one. Nothing changes until Confirm.
2. **Adjust**: HR can change carry and encash per line (carry + encash ≤ unused; lapse is the rest). Each
   change is audited (`LEAVE_YEAR_END_LINE_ADJUSTED`) and the line is marked Adjusted.
3. **Confirm**, which does all of the following:
   - Writes one `leave_carry_forward_transfers` row per line: unused, carried, encashed, lapsed, expiry, run
     and adjusted.
   - Adds the carried days to next year's carried-forward bucket and creates next year's balance row for
     everyone in the run, including those carrying nothing.
   - Queues each priced encashment in `leave_encashments` as PENDING.
   - Sends every employee their outcome in the app (committed with the year-end) and by email
     (`app.leave.notices.email`).
   - Payroll then adds each pending encashment as a **"Leave Encashment" ad-hoc payment** to the next draft
     it generates or regenerates that includes the employee (ADDED). If the component is missing or
     inactive, the encashment waits.
4. **Rollback** of the confirmed run is allowed within `app.leave.year-end.rollback-days` (default 7).
   - It removes the run's transfers and the carried days from next year's balances.
   - It cancels the encashments, removing their payments from draft payroll runs.
   - It is refused when a carried-forward day has already been used or expired, or when an encashment is in
     a payroll run past draft.
   - Employees are not notified of a rollback. HR can then preview and confirm again.

One PREVIEW and one CONFIRMED run per year (partial unique indexes). Top-ups (a request pending at
year-end rejected later) raise a transfer's carry up to the cap, never past `unused − encashed`, and never
for a line HR adjusted. A transfer is never lowered. Carried days of a transfer expire on its date, as
before.

**Not built:** every movement as a unified ledger entry (LV-11). The run lines, transfers and encashments
are the record.

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
  balance afterwards under Balances → Employee balances → Adjust; every adjustment is audited
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

## Rollback (V321)

Drop `leave_plan_allocations`, `leave_plan_type_overrides`, `leave_plan_type_eligibility`,
`leave_plan_type_rules`, `leave_plan_versions` and `leave_plans`, and delete `assignment_rules` /
`assignment_rule_assignments` rows of domain `LEAVE_PLAN`. The app before LV-01 reads the old tables, which
V321 left untouched.

## Rollback (V327)

The previous leave year cannot be closed automatically again once these are dropped. Drop
`leave_encashments` (cancel any PENDING / ADDED ones first: remove their ad-hoc payments from draft runs),
`leave_year_end_run_lines` and `leave_year_end_runs`. Then drop `leave_carry_forward_transfers.encashed_days`,
`run_id` and `adjusted`, and the seven `leave_plan_type_rules` year-end columns (`carry_forward_max_percent`,
`carry_forward_expiry_month`, `carry_forward_expiry_day`, `encash_enabled`, `encash_max_days`,
`encash_basis`, `encash_divisor`).

## Rollback (V326)

Delete `leave_accrual_credits` rows with `source = 'PAUSED'` (the next run then credits those months), set
`QUARTERLY` / `HALF_YEARLY` rules back to `MONTHLY`, restore the two CHECK constraints (`credit_frequency IN
('YEARLY','MONTHLY')`, `source IN ('ACCRUAL','MIGRATION')`) and drop `leave_plan_type_rules.credit_timing`,
`accrual_pause_lop_over_days` and `accrual_pause_unpaid_over_days`.

## Rollback (V325)

Drop `leave_exit_settlements` and `leave_plans.proration_method`, `proration_cutoff_day`, `proration_rounding`.
Payroll's exit proration then reads the plain available balance again.

## Rollback (V324)

Delete `leave_plan_type_eligibility` rows of `MARITAL_STATUS` / `GRADE`, restore the four-value `rule_type` CHECK,
and drop `leave_plan_type_rules.min_tenure_months` and `allowed_during_probation`.

## Rollback (V323)

Drop the four `sandwich_*` columns of `leave_plan_type_rules` and `leave_requests.day_counts`. Requests saved
since V323 may span non-working days: their `total_days` stays right, but older code would split a cross-year
one by calendar days.

## Rollback (V322)

Drop `leave_request_attachments` and `leave_plan_type_clubbing` and the LV-02 columns of
`leave_plan_type_rules` (`entitlement_mode` through `attachment_max_mb`). Requests saved as `HOURLY` keep their
`leave_hours`; the app before LV-02 shows them as full days.

## Audit actions

`LEAVE_TYPE_CREATED`, `LEAVE_TYPE_UPDATED`, `LEAVE_TYPE_DEACTIVATED`, `LEAVE_TYPE_REACTIVATED`,
`LEAVE_TYPE_DELETED`, `LEAVE_ENTITLEMENT_CHANGED`, `LEAVE_CARRY_FORWARD_CONFIG_CHANGED`,
`LEAVE_YEAR_END_PROCESSED`, `LEAVE_CARRY_FORWARD_TOPPED_UP`, `LEAVE_CARRY_FORWARD_EXPIRED`,
`LEAVE_BALANCE_ADJUSTED`, `LEAVE_ACCRUAL_PROCESSED`, `LEAVE_TYPE_APPLICABILITY_CHANGED` (before V321).

Leave plans (V321): `LEAVE_PLAN_CREATED`, `LEAVE_PLAN_UPDATED`, `LEAVE_PLAN_CLONED`, `LEAVE_PLAN_STATUS_CHANGED`,
`LEAVE_PLAN_SET_AS_DEFAULT`, `LEAVE_PLAN_DELETED`, `LEAVE_PLAN_VERSION_SAVED`, `LEAVE_PLAN_ALLOCATION_ASSIGNED`,
`LEAVE_PLAN_ALLOCATION_UPDATED`, `LEAVE_PLAN_ALLOCATION_REMOVED`, `LEAVE_PLAN_ALLOCATION_BULK_ASSIGNED`,
`LEAVE_PLAN_ALLOCATION_BULK_REMOVED`, and the shared `ASSIGNMENT_RULE_*` actions for rules.

Year-end wizard (V327): `LEAVE_YEAR_END_RUN_PREVIEWED`, `LEAVE_YEAR_END_LINE_ADJUSTED`,
`LEAVE_YEAR_END_RUN_CONFIRMED`, `LEAVE_YEAR_END_RUN_ROLLED_BACK`. Employees get a `LEAVE_YEAR_END_OUTCOME`
notification.
