# Leave configuration (V310, V314, V321, V322)

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
- `LeaveAccrualService` runs nightly (`app.leave.accrual.cron`, default 00:15 in the business zone;
  in January it also finishes the previous year). HR can run it from Balances → Run crediting now. Balances are also brought up to date whenever they are read.
- "Re-credit months already credited" when saving plan rules re-credits those months at the new rate and
  adds the difference (manual adjustments survive). From 1 January it also resets V314-migrated balances.
- Migration: every balance of the current and earlier years got 0-day `MIGRATION` rows for all 12
  months, so those years count as fully credited and nothing is added on top. Future-year rows restart
  at 0 credited. Paid Leave's policy is 15 days `YEARLY` up to the migration year and 15 days `MONTHLY`
  from the next year.

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

## Year-end processing

`LeaveYearEndService` runs nightly (`app.leave.year-end.cron`, default 00:30 in the business zone). HR can
also run it from Year-End → Run now. For the leave year that just ended:

1. For every active employee and regular paid type, it computes unused days: current-year days left, plus
   carried-forward days still valid at year end, minus pending leave still reserved in that year.
2. When carry-forward is enabled in the employee's plan on the last day of the year, it carries
   `min(unused, max days)` into next year's carried-forward bucket, with expiry N months into the new year.
   Everything else lapses.
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
