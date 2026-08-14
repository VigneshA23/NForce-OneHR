---
title: NForce OneHR — Feature Implementation & Enhancement History
scope: Request Regularization · Cancel Penalties · Team Punctuality · Penalization Policy
generated: 2026-08-13
audience: Management (primary) + Engineering (technical appendix)
---

# HR Feature Implementation & Enhancement History

**Purpose of this document:** give management a clear, evidence-based picture of what was originally built, what changed later, why it changed, what problems were found, what was tested, and what is currently pending — for four features: **Request Regularization, Cancel Penalties, Team Punctuality, Penalization Policy.**

**Evidence sources used to build this document:** `git log` / `git log --follow` / `git show --stat` per-file and per-commit history, Flyway migration files, source code and inline documentation, test files, JUnit surefire test reports, and current `git status` (uncommitted work). Every date below is either a real Git commit timestamp, a file-modification timestamp (explicitly labeled), or is marked **"Date not determinable from repository history"** where no evidence exists. No date, bug, or test result has been invented.

**Repository commit range covered:** `4458e6a` (25-Jul-2026, initial commit) → `7829370` (12-Aug-2026, latest merge to `main`), plus uncommitted working-tree changes dated **13-Aug-2026** (today).

---

## Executive Summary

| Feature | Original Implementation | Latest Enhancement | Current Status |
|---|---|---|---|
| **Request Regularization** | 31-Jul-2026 | 13-Aug-2026 | Implemented; **uncommitted** as of 13-Aug-2026 |
| **Cancel Penalties** | 12-Aug-2026 | No dedicated enhancement since original (upstream-only touch, 13-Aug-2026) | Implemented and Verified; committed |
| **Team Punctuality** | 12-Aug-2026 | 12-Aug-2026 (mobile/tablet responsiveness) | Implemented and Verified; committed |
| **Penalization Policy** | 10-Aug-2026 (assignment) / 12-Aug-2026 (rule engine) | 13-Aug-2026 | Implemented; **uncommitted** as of 13-Aug-2026 |

**Final backend test verification:** 299/299 tests passing, 0 failures, 0 errors — the complete backend suite (25 surefire report files, covering all 24 `*Test.java` files plus `OneHrApplicationTests.java`).

**Most important open item:** all of the 13-Aug-2026 work across Request Regularization and Penalization Policy — 52 changed files in total — is **not yet committed to Git**. It has not been merged, code-reviewed as a PR, or deployed.

---
---

# 1. Request Regularization

## 1. Original Implementation

**Original Implementation Date:** 31-Jul-2026

**Initial Purpose:**
Allow an employee to request a correction to their attendance record (e.g., a missed check-in/check-out) and have it reviewed and approved by an authorized approver, instead of the raw attendance log being the final word.

**Original Implementation / What Was Delivered:**
- `RegularizationRequest` entity and `RegularizationService`
- Employee-facing submit and "my requests" endpoints
- A single-stage approval action
- Database schema for attendance + regularization (migration, originally numbered V12, present in the codebase today as `V17__create_attendance_and_regularization.sql`)

---

## 2. Enhancement History

| Date | Enhancement | What Changed | Why It Changed | Files / Modules Affected | Tests / Verification |
|---|---|---|---|---|---|
| 03-Aug-2026 | Two-stage approval workflow | Added a `RegularizationApproval` entity and a Manager → HR Admin/Super Admin approval chain; added a dedicated Super Admin review page | Single-stage approval was not sufficient oversight for regularization requests | `RegularizationService`, `RegularizationApproval` entity, `V40`/`V41` migrations, `SuperAdminRegularizationPage.tsx` | `RegularizationServiceTest` expanded by +353 lines (commit `b4668de`) |
| 04-Aug-2026 | One-approval-per-date safeguard + attendance page rework | Added a database constraint preventing two approved regularizations for the same date; reworked the employee attendance page UI | Prevent conflicting/duplicate approved corrections for the same date; improve usability | `V47` migration, `SecurityConfig`, `AttendancePage.tsx` | `RegularizationServiceTest` extended (commit `dbb0c91`) |
| 05-Aug-2026 | Bulk approve/reject + partially-approved status | Added bulk approve/reject actions for approvers; added a `PARTIALLY_APPROVED` status with per-stage and actor-role tracking | Let approvers act on multiple pending requests at once; track two-stage approval progress accurately | `RegularizationService`, 3 new migrations (`V51`/`V52`/`V53`), new bulk-request DTOs | `AttendanceControllerTest` (+78 lines) and `RegularizationServiceTest` (+263 lines) (commit `9c9c58b`) |
| 06-Aug-2026 (morning) | Approval restriction refinements | Adjusted rules governing who/when a regularization can be approved | Tighten business rules around approval eligibility | `RegularizationService`, `application.yml` | `RegularizationServiceTest` extended (commit `728f7ec`) |
| 06-Aug-2026 (afternoon) | Audit logging integration | Wired regularization actions into the organization-wide audit log | Company-wide requirement for HR/Super Admin audit visibility across all major actions | `RegularizationService` + `AuditService`/`AuditActionCategory` | Part of a broader "Audit for superadmin and HR" commit (`3f2dbc3`) covering multiple services; no dedicated regularization-only test noted |
| 12-Aug-2026 | Integration touch with the new penalty/exception pipeline | Small adjustment (17 lines) connecting regularization state to the newly introduced attendance-exception/penalty detection pipeline (see Penalization Policy) | Support the same-day introduction of Cancel Penalties/Penalization Policy | `RegularizationService.java` | Covered under the broad test additions of commit `39d7e80` |
| **13-Aug-2026 (uncommitted)** | **Notifications, 07:00 AM business-day boundary, Access-Denied investigation** | See §3 below | See §3 below | `RegularizationService.java`, `RegularizationServiceTest.java` | 11 new deterministic tests; full suite 299/299 |

---

## 3. Current Latest Enhancement

**Latest Enhancement Date:** 13-Aug-2026

**Why:**
Business asked for two things: (1) automatic notifications so employees and approvers no longer need to manually check request status, and (2) a change to how "today" is calculated for regularization so that work done in the early hours of the morning (before 7 AM) counts toward the previous business day. A separately reported "HR Admin gets Access Denied" issue was also investigated as part of this work.

**What Changed:**
- Added **Created**, **Approved**, and **Rejected** notifications for regularization requests, reusing the notification system already used elsewhere in the product (no new notification framework was built).
- Added a **07:00 AM business-day boundary**: regularization's own "today" (used for its lookback window and monthly-request-limit checks) now rolls over at 7:00 AM local time instead of midnight.

**Bug(s) Fixed:**
None. See below.

**Investigated — no defect found:**
The reported "HR Admin incorrect Access Denied" issue was investigated by directly inspecting the authorization rule on every regularization-related endpoint (submit, approve, reject, bulk approve/reject, history, pending, all). No endpoint or code path was found that would incorrectly deny access to an HR Admin. This is an investigation outcome, not a bug fix.

**Tests:**
11 new tests added to `RegularizationServiceTest` (4 covering the 07:00 AM boundary with fixed, deterministic timestamps; 7 covering the three notification events). Full backend regression suite verified at **299/299 passing, 0 failures, 0 errors** at the end of this work.

**Current Status:** **Implemented; uncommitted as of 13-Aug-2026.**

---

## 4. Feature Timeline

```
Original implementation — submit + single-stage approval (31-Jul-2026)
        ↓
Two-stage approval workflow added (03-Aug-2026)
        ↓
One-approval-per-date safeguard + UI rework (04-Aug-2026)
        ↓
Bulk approve/reject + partially-approved status (05-Aug-2026)
        ↓
Approval restriction refinements (06-Aug-2026)
        ↓
Audit logging integration (06-Aug-2026)
        ↓
Integration with new penalty/exception pipeline (12-Aug-2026)
        ↓
Notifications + 07:00 AM boundary + Access-Denied investigation (13-Aug-2026) ← current, uncommitted
```

---

## 5. Current Status Summary

**Feature:** Request Regularization

**Original:** 31-Jul-2026

**Latest Enhancement:** 13-Aug-2026

**Why:** Business-day calculation needed to change from midnight to 7 AM, and lifecycle notifications were required.

**What:** Added a 7 AM business-day boundary and Created/Approved/Rejected notifications.

**Bug:** HR Admin "Access Denied" was investigated. No authorization defect was found in the controller, service, or frontend flow.

**Tests:** 299/299 backend tests passing at final verification.

**Status:** Implemented; uncommitted as of 13-Aug-2026.

---
---

# 2. Cancel Penalties

## 1. Original Implementation

**Original Implementation Date:** 12-Aug-2026

**Initial Purpose:**
Give Managers, HR Admins, and Super Admins the ability to cancel an attendance penalty that was wrongly or unnecessarily applied to one of their team members, instead of the penalty standing automatically.

**Original Implementation / What Was Delivered:**
- `AttendancePenalty` entity and `AttendancePenaltyStatus` status values
- `AttendancePenaltyService` — manager-scoped penalty listing (a Manager sees only their own direct reports' penalties) and bulk/single cancellation
- Only penalties in `PENDING_REVIEW` or `APPLIED` status can be cancelled
- Penalties are automatically hidden from the cancellable list while an active regularization for the same date exists
- Every cancellation is recorded in the audit log (`ATTENDANCE_PENALTY_CANCELLED`)
- Database table for attendance penalties (`V103`, extended same-day by `V105` to add a deduction-days column)
- Dedicated test coverage (`AttendancePenaltyServiceTest`, 182 lines)

---

## 2. Enhancement History

| Date | Enhancement | What Changed | Why It Changed | Files / Modules Affected | Tests / Verification |
|---|---|---|---|---|---|
| — | *No dedicated enhancement to Cancel Penalties' own code has been committed since original implementation.* | — | — | — | — |
| **13-Aug-2026 (uncommitted)** | Upstream-only: penalty-evaluation pipeline enhanced (see Penalization Policy) | **No change to `AttendancePenaltyService.java` or the `AttendancePenalty` entity.** The separate engine that *creates* penalty records (`AttendancePenaltyEvaluationService`, `ExceptionService`) was enhanced for multi-policy support and additional rules | Broaden how penalties get created, without touching the already-working cancellation workflow | `AttendancePenaltyEvaluationService`, `ExceptionService`, `ConfiguredAttendancePolicyEngine` (Cancel Penalties' own files are untouched) | Indirect coverage via `PenaltyDeductionServiceTest`, `MultiPolicyAssignmentIsolationTest`, etc. — see Penalization Policy section |

---

## 3. Current Latest Enhancement

**Latest Enhancement Date:** No dedicated enhancement since original implementation (12-Aug-2026). An upstream-only touch to the penalty-*creation* pipeline occurred 13-Aug-2026 (uncommitted) but did not modify this feature's own code.

**Why:** Not applicable — no direct enhancement was made to this feature.

**What Changed:** Nothing in the cancellation workflow, its authorization, or its audit trail. Only the separate upstream engine that generates penalty records in the first place was enhanced (documented under Penalization Policy).

**Bug(s) Fixed:** None found or reported for this feature specifically, in original implementation or since.

**Tests:** Original `AttendancePenaltyServiceTest` (182 lines, 12-Aug-2026) is the dedicated test coverage; it is included in the current full-suite result of 299/299 passing.

**Current Status:** **Implemented and Verified; committed** (no uncommitted changes to this feature's own code).

---

## 4. Feature Timeline

```
Original implementation — cancel/list workflow, audit logging (12-Aug-2026)
        ↓
No further committed changes to this feature's own code
        ↓
(Upstream, uncommitted) Penalization Policy Phase 1/2 changes how
penalties are generated — Cancel Penalties workflow itself unaffected (13-Aug-2026)
```

---

## 5. Current Status Summary

**Feature:** Cancel Penalties

**Original:** 12-Aug-2026

**Latest Enhancement:** None committed since original implementation.

**Why:** Not applicable.

**What:** No functional change; the feature has been stable since it was first delivered.

**Bug:** None found for this feature specifically.

**Tests:** 182-line dedicated test suite from original delivery, included in the current 299/299 full-suite pass.

**Status:** Implemented and Verified; committed.

---
---

# 3. Team Punctuality

## 1. Original Implementation

**Original Implementation Date:** 12-Aug-2026 (the punctuality dashboard itself). *Note: the host page, `MyTeamPage.tsx`, was first created on 07-Aug-2026 as a basic manager team-roster view with no punctuality metrics — punctuality functionality was added five days later.*

**Initial Purpose:**
Give Managers, HR Admins, and Super Admins a dashboard showing their team's on-time/late attendance behavior — including a punctuality leaderboard — without needing to inspect raw attendance logs manually.

**Original Implementation / What Was Delivered:**
- `/attendance/team-punctuality` API endpoint
- Punctuality summary, daily punctuality, and leaderboard data structures, computed from existing attendance/punch data (no new attendance-capture mechanism was introduced)
- Dashboard UI added to `MyTeamPage.tsx` (a 427-line addition)
- Dedicated test coverage (`AttendanceServicePunctualityTest`, 189 lines)

---

## 2. Enhancement History

| Date | Enhancement | What Changed | Why It Changed | Files / Modules Affected | Tests / Verification |
|---|---|---|---|---|---|
| 11-Aug-2026 | Late-arrival & midnight-crossover accuracy fix *(upstream, one day before the dashboard's own first commit)* | Fixed late-arrival minutes to be measured from shift start rather than the grace-period deadline (a sub-minute-late check-in previously could floor to "0m" and read as on-time); fixed the open check-in/check-out session lookup so a shift crossing midnight is tracked correctly | Punctuality figures for night-shift/midnight-crossing employees were at risk of being inaccurate; the old lookup also crashed for employees with more than one open session | `AttendanceService`, `WebClockInService`, `AttendancePage`/`DashboardPage` (live UI refresh) | Predates the dashboard's own dedicated test suite by less than 24 hours; the fix is foundational to the punctuality figures the dashboard later displayed |
| 12-Aug-2026 (mid-day) | KPI row alignment fix | Fixed visual alignment of the KPI row on the team page | Cosmetic/usability fix — bundled into a commit whose main purpose was an unrelated peer-kudos feature (ONEHR-73) | `MyTeamPage.tsx` | No dedicated test noted (UI-only change) |
| 12-Aug-2026 (evening) | Mobile/tablet responsiveness | Adjusted layout for smaller screens | Company-wide initiative to make the application usable on mobile and tablet devices | `MyTeamPage.tsx` (and other pages, repository-wide) | No dedicated test noted (UI-only change) |

No changes to the punctuality calculation logic, filters, or API itself have been committed since the original implementation.

---

## 3. Current Latest Enhancement

**Latest Enhancement Date:** 12-Aug-2026 (mobile/tablet responsiveness pass)

**Why:** Company-wide initiative to make the application usable on mobile and tablet devices — not a business-logic change specific to punctuality.

**What Changed:** Layout/responsiveness adjustments only. Punctuality calculations, filters, and the underlying API have been unchanged since original delivery.

**Bug(s) Fixed:** None in the punctuality dashboard's own logic. A related upstream fix on 11-Aug-2026 (see table above) corrected late-arrival minute calculation and midnight-crossing session handling in the shared attendance service that the dashboard's figures depend on.

**Tests:** `AttendanceServicePunctualityTest` (189 lines, from original implementation) remains the dedicated test coverage and is included in the current 299/299 full-suite pass. No dedicated automated tests were added for the KPI-alignment or responsiveness commits, consistent with them being UI-only changes.

**Current Status:** **Implemented and Verified; committed.**

---

## 4. Feature Timeline

```
MyTeamPage.tsx created as a basic team view — no punctuality features (07-Aug-2026)
        ↓
(Upstream) Late-arrival / midnight-crossover accuracy fix (11-Aug-2026)
        ↓
Team Punctuality dashboard delivered — endpoint, calculations, leaderboard (12-Aug-2026)
        ↓
KPI row alignment fix (12-Aug-2026)
        ↓
Mobile/tablet responsiveness (12-Aug-2026)
```

---

## 5. Current Status Summary

**Feature:** Team Punctuality

**Original:** 12-Aug-2026 (dashboard); host page existed since 07-Aug-2026 without punctuality features

**Latest Enhancement:** 12-Aug-2026 (mobile/tablet responsiveness)

**Why:** Company-wide mobile/tablet usability initiative; no business-logic change requested.

**What:** Visual layout adjustments only.

**Bug:** No bug in the dashboard's own logic. An upstream fix (11-Aug-2026) improved the accuracy of the late-arrival data it displays.

**Tests:** 189-line dedicated test suite from original delivery, included in the current 299/299 full-suite pass.

**Status:** Implemented and Verified; committed.

---
---

# 4. Penalization Policy

## 1. Original Implementation

**Original Implementation Date:** 10-Aug-2026 (policy assignment concept) and 12-Aug-2026 (the functioning rule engine).

**Initial Purpose:**
Let HR configure attendance-penalty rules for the organization (No Attendance, Missing Logs, Work Hours Shortage, Late Arrival) and have violations automatically detected and penalized consistently, instead of being handled manually.

**Original Implementation / What Was Delivered:**
- **10-Aug-2026:** `Shift`, `WeeklyOffPolicy`, and `PenalisationPolicy` entities, plus bulk employee-assignment tooling (including CSV import) — this delivered the ability to *label and assign* a policy to employees, with no rule-evaluation logic behind it yet.
- **12-Aug-2026:** The actual rule engine — versioned policy configuration (`PenalizationPolicyVersion`), `PenalizationPolicyService`, `ConfiguredAttendancePolicyEngine`, exception-detection logic in `ExceptionService`, working-day calculation (`WorkingDayService`), and the HR configuration screen. This delivered No Attendance, Late Arrival (grace-period basis), Work Hours Shortage (tiered), and Missing Logs detection — but only as a **single, organization-wide policy**, not yet properly linked to the assignment layer delivered two days earlier.

---

## 2. Enhancement History

| Date | Enhancement | What Changed | Why It Changed | Files / Modules Affected | Tests / Verification |
|---|---|---|---|---|---|
| **13-Aug-2026 (uncommitted)** | **Phase 1** | Linked policy versions to specific named/assignable policies (previously versions were global, not tied to an assignment); added Basic Info configuration fields, widened WEEK/MONTH penalty-cycle options, added notice-period/deduction-outcome fields; added a **scheduled background job** as a second automatic evaluation trigger point (alongside the existing dashboard-load trigger); added a dedicated deduction service routing penalties to Loss of Pay or Paid Leave | Close functional gaps against the full internal requirements specification; make policy versions actually apply to the policy they were assigned to, rather than being effectively global | `V106` migration; new `PenalizationPolicyLateHoursTier`, `BasicInfoConfigDto`; changes to `ConfiguredAttendancePolicyEngine`, `AttendancePenaltyEvaluationService`, `ExceptionService`; scheduling enabled on the application | New: `PenaltyDeductionServiceTest`, `PenaltyEvaluationSchedulerTest`. Extended: `ConfiguredAttendancePolicyEngineTest`, `ExceptionServiceTest`, `PenalizationPolicyProductionFlowTest`, `PenalizationPolicyServiceTest`, `AttendancePenaltyEvaluationServiceTest` |
| **13-Aug-2026 (uncommitted)** | **Phase 2** | Added true **multi-policy management** (create/rename/clone/list named policies); added a "Total Hours" basis for Late Arrival (in addition to the existing grace-period basis); added combined-rule handling; added missing-log-caused late-arrival attribution; added holiday/week-off "sandwich" handling; added half-day-leave interaction with penalty calculation | Support multiple distinct penalty policies across the organization, and cover the remaining business rules from the specification | `V107` migration; new policy-management service/controller; new policy-list UI and API client; updated org setup page | New: `MultiPolicyAssignmentIsolationTest`, `PenalisationPolicyManagementServiceTest` |
| **13-Aug-2026 (uncommitted)** | **Final Deep Verification audit** | An independent, exhaustive audit of actual runtime behavior (not just "do the tests pass") found and fixed **one real defect**: an employee with no explicit policy assignment could be ambiguously matched to the wrong policy's rules once more than one policy existed | Required before treating a multi-policy system as production-ready — verify real business behavior, not only test-pass status | Policy-version resolution logic (new deterministic default-policy resolution) | `MultiPolicyAssignmentIsolationTest` (a Mockito test-ordering flake was also fixed during this work); full regression suite re-run |
| **13-Aug-2026 (uncommitted)** | Tier-validation & week-off ANY/AFTER test-coverage follow-up | Added direct test coverage for two areas flagged by the audit as under-tested: (a) tier-threshold validation (duplicate/over-100% rejection), (b) week-off ANY/AFTER adjacency logic. **Both were independently confirmed to already work correctly — zero production code was changed.** | Close specific, narrowly-scoped test-coverage gaps identified by the audit without touching already-working business logic | `PenalizationPolicyServiceTest`, `ExceptionServiceDetectionTest` (test files only) | Full backend suite verified at 299/299 |

---

## 3. Current Latest Enhancement

**Latest Enhancement Date:** 13-Aug-2026 (uncommitted)

**Why:**
Move from a single, global, org-wide penalty policy to a fully configurable multi-policy system matching the internal requirements specification, and then independently verify that the result behaves correctly in practice — not just that its automated tests pass.

**What Changed:**
Multiple distinct penalty policies can now be created and assigned to different groups of employees. Late Arrival can be evaluated on a cumulative "Total Hours" basis in addition to grace-period lateness. A scheduled background job now evaluates penalties automatically, in addition to the existing dashboard-triggered path. Deductions can route to Loss of Pay or Paid Leave.

**Bug(s) Fixed:**
- **Multi-policy assignment isolation bug** — an employee with no explicit policy assignment could be ambiguously matched to the wrong policy's rules once multiple policies existed in the system. Fixed with a deterministic default-policy resolution rule.

**Investigated — no defect found:**
- Tier-threshold validation (duplicate thresholds, thresholds over 100%) — confirmed already correct; test coverage added, no code changed.
- Week-off ANY/AFTER adjacency logic — confirmed already correct; test coverage added, no code changed.

**Tests:**
Full backend regression suite verified at **299/299 passing, 0 failures, 0 errors** at the end of this work (the complete suite — 25 surefire report files). An earlier **288/288** milestone referenced during this work **could not be independently confirmed from repository evidence** — the local test-report folder is overwritten on every run, and no other repository artifact records that intermediate count. Treat it as **"Date not determinable from repository history"** rather than a verified figure.

**Current Status:** **Implemented; uncommitted as of 13-Aug-2026.**

**Known structural limitations (documented in code, not defects):**
- **Total Hours vs. per-shift basis:** the engine deliberately evaluates "Total Late Hours in Shift" and the cumulative "Total Hours" basis as **one mechanism**, matched against a cycle-cumulative total rather than tracked as two fully independent figures. This is documented in the code as a deliberate simplification consistent with the approved specification examples, not an unfinished feature.
- **Missing-IN structural note:** Missing-log detection is implemented for the case of a recorded check-in with no matching check-out. The codebase does not implement a symmetric path for the reverse case (a check-out with no check-in), which is consistent with the system's attendance model — a check-out can only ever be recorded against an already-open check-in session, so that reverse case cannot occur.

---

## 4. Feature Timeline

```
Policy assignment concept created — Shift/WeeklyOff/PenalisationPolicy
entities, bulk employee assignment (10-Aug-2026)
        ↓
Rule engine delivered — versioned config, detection logic, HR config
screen, single global policy (12-Aug-2026)
        ↓
Phase 1 — engine/deduction/scheduler wiring, policy-version linkage (13-Aug-2026)
        ↓
Phase 2 — true multi-policy management, Total Hours basis, combined
rules, holiday/week-off handling (13-Aug-2026)
        ↓
Final Deep Verification audit — multi-policy isolation bug found and fixed (13-Aug-2026)
        ↓
Tier-validation & week-off test-coverage follow-up — no further
code changes needed (13-Aug-2026) ← current, uncommitted
```

---

## 5. Current Status Summary

**Feature:** Penalization Policy

**Original:** 10-Aug-2026 (policy assignment) / 12-Aug-2026 (functioning rule engine)

**Latest Enhancement:** 13-Aug-2026

**Why:** Move from one global policy to a fully configurable multi-policy system per the internal requirements specification, then independently verify it behaves correctly.

**What:** Multi-policy management, Total Hours late-arrival basis, combined rules, holiday/week-off handling, scheduled automatic evaluation, Loss-of-Pay/Paid-Leave deduction routing.

**Bug:** A multi-policy assignment isolation bug (unassigned employees could match the wrong policy) was found and fixed. Tier-validation and week-off ANY/AFTER logic were investigated — no defect found in either.

**Tests:** 299/299 backend tests passing at final verification. An earlier 288/288 milestone could not be confirmed from repository evidence.

**Status:** Implemented; uncommitted as of 13-Aug-2026.

---
---

# Cross-Feature Master Timeline

| Date | Feature | Change | Reason | Status |
|---|---|---|---|---|
| 30-Jul-2026 | *(shared infrastructure)* | Notification system created | Foundation later reused by Request Regularization | Committed |
| 31-Jul-2026 | Request Regularization | Original implementation | Enable employee attendance-correction requests | Committed |
| 03-Aug-2026 | Request Regularization | Two-stage approval added | Stronger oversight than single-stage approval | Committed |
| 04-Aug-2026 | Request Regularization | One-approval-per-date safeguard + UI rework | Prevent duplicate approved corrections; usability | Committed |
| 05-Aug-2026 | Request Regularization | Bulk approve/reject, partially-approved status | Efficiency for approvers; track two-stage progress | Committed |
| 06-Aug-2026 | Request Regularization | Approval restriction refinements | Tighten approval eligibility rules | Committed |
| 06-Aug-2026 | Request Regularization | Audit logging integration | Company-wide audit visibility requirement | Committed |
| 07-Aug-2026 | Team Punctuality *(precursor)* | Basic My Team view created | Give managers a team roster page | Committed |
| 10-Aug-2026 | Penalization Policy | Assignment layer created (Shift/WeeklyOff/PenalisationPolicy) | Enable assigning policies to employees | Committed |
| 11-Aug-2026 | Team Punctuality *(upstream)* | Late-arrival & midnight-crossover fix | Correct punctuality data accuracy at the source | Committed |
| 12-Aug-2026 | Request Regularization | Integration touch with new penalty pipeline | Support same-day introduction of Cancel Penalties/Penalization Policy | Committed |
| 12-Aug-2026 | Cancel Penalties | Original implementation | Let managers cancel wrongly-applied penalties | Committed |
| 12-Aug-2026 | Team Punctuality | Dashboard delivered | Give managers visibility into team punctuality | Committed |
| 12-Aug-2026 | Penalization Policy | Rule engine delivered | Automate penalty detection against configured rules | Committed |
| 12-Aug-2026 | Team Punctuality | KPI row alignment fix | Cosmetic fix | Committed |
| 12-Aug-2026 | Team Punctuality | Mobile/tablet responsiveness | Company-wide usability initiative | Committed |
| **13-Aug-2026** | Request Regularization | Notifications + 07:00 AM boundary + Access-Denied investigation | Automatic status updates; correct business-day handling | **Uncommitted** |
| **13-Aug-2026** | Penalization Policy | Phase 1, Phase 2, Deep Verification audit, tier/week-off test follow-up | Multi-policy support per specification; independent verification | **Uncommitted** |

---

# Cross-Feature Summary

| Feature | Original Date | Latest Enhancement | Major Enhancements | Bugs / Issues | Latest Verification | Current Status |
|---|---|---|---|---|---|---|
| **Request Regularization** | 31-Jul-2026 | 13-Aug-2026 | Two-stage approval; bulk approve/reject; audit logging; notifications; 07:00 AM boundary | Access-Denied — investigated, no defect found | 299/299 | Implemented; uncommitted |
| **Cancel Penalties** | 12-Aug-2026 | None committed since original | — | None found | 299/299 (via original 182-line suite) | Implemented and Verified; committed |
| **Team Punctuality** | 12-Aug-2026 | 12-Aug-2026 | Upstream late-arrival/midnight-crossover fix; KPI alignment; mobile/tablet responsiveness | None in dashboard's own logic | 299/299 (via original 189-line suite) | Implemented and Verified; committed |
| **Penalization Policy** | 10-Aug-2026 / 12-Aug-2026 | 13-Aug-2026 | Phase 1 (engine/deduction/scheduler); Phase 2 (multi-policy, Total Hours basis); Deep Verification audit | Multi-policy isolation — bug found & fixed; tier-validation & week-off — investigated, no defect found | 299/299 | Implemented; uncommitted |

---

# Bugs and Issues Fixed

| Feature | Date | Issue / Bug | Investigation / Fix | Result |
|---|---|---|---|---|
| Penalization Policy | 13-Aug-2026 | Multi-policy assignment isolation: an employee with no explicit policy assignment could resolve to the wrong policy's rules once multiple policies existed | Root-caused to an unscoped fallback in policy-version resolution; fixed with a deterministic default-policy resolution rule | **Bug fixed** |
| Team Punctuality *(upstream)* | 11-Aug-2026 | Late-arrival minutes could floor to "0m" for sub-minute-late check-ins; open-session lookup crashed for employees with 2+ open sessions across midnight | Late-minutes now measured from shift start; open-session lookup changed to "most recent open session" | **Bug fixed** |
| Request Regularization | 13-Aug-2026 | Reported: HR Admin sees an incorrect "Access Denied" | Every regularization endpoint's authorization rule was inspected directly; no path found that would incorrectly deny an HR Admin | **Investigated — no defect found** |
| Penalization Policy | 13-Aug-2026 | Flagged in audit as under-tested: tier-threshold validation (duplicate/over-100% thresholds) | Existing validation logic confirmed already correct; test coverage added only | **Investigated — no defect found** (test-only change) |
| Penalization Policy | 13-Aug-2026 | Flagged in audit as under-tested: week-off ANY/AFTER adjacency logic | Existing logic confirmed already correct; test coverage added only | **Investigated — no defect found** (test-only change) |

**Known limitations (not bugs):**
- Penalization Policy models "Total Late Hours in Shift" and the cumulative "Total Hours" basis as one mechanism rather than two independent facts (deliberate simplification, documented in code).
- Missing-log detection covers "check-in with no check-out"; the reverse case does not occur given how check-out is structurally tied to an open check-in session.
- `RegularizationServiceTest` fixtures that compute "today" via the system clock are theoretically time-of-day-sensitive if ever run before 07:00 local time, now that the 07:00 AM boundary exists. This is disclosed in code comments; no `Clock` abstraction (a pattern not used elsewhere in this codebase) was introduced to address it.

**Pending items:**
- All Penalization Policy Phase 1/2/audit/test-follow-up work and the Request Regularization notifications/07:00-boundary work — **52 uncommitted files**, not yet merged or deployed.

---

# Testing & Verification History

| Feature | Dedicated test coverage | Status |
|---|---|---|
| Request Regularization | `RegularizationServiceTest` — grown from original implementation through every enhancement; +11 tests this session (4 boundary, 7 notification) | Included in current full-suite pass |
| Cancel Penalties | `AttendancePenaltyServiceTest` (182 lines, original implementation) | Included in current full-suite pass |
| Team Punctuality | `AttendanceServicePunctualityTest` (189 lines), `AttendanceServiceTeamStatsTest` | Included in current full-suite pass |
| Penalization Policy | `PenalizationPolicyServiceTest`, `PenalizationPolicyProductionFlowTest`, `ConfiguredAttendancePolicyEngineTest`, `PenaltyDeductionServiceTest`, `PenaltyEvaluationSchedulerTest`, `PenalisationPolicyManagementServiceTest`, `MultiPolicyAssignmentIsolationTest`, `ExceptionServiceDetectionTest` | Included in current full-suite pass |

**Final overall backend test count:** **299/299 passing, 0 failures, 0 errors** — verified directly from `backend/target/surefire-reports/` (25 report files, covering all 24 `*Test.java` files plus `OneHrApplicationTests.java` — the complete backend suite, not a partial run).

**Intermediate milestones (251/251, 266/266, 280/280, 288/288):** referenced during the course of this work but **not independently verifiable from repository evidence** — the local surefire-report folder is overwritten on every test run, and no other repository artifact records these intermediate counts. **Date not determinable from repository history** for each of these.

**Frontend build/lint/type-check:** No repository artifact (build log, CI report) was found confirming a specific `tsc`/lint/build pass/fail result for this session's frontend changes. A `frontend/dist/` build output exists in the repository but its freshness relative to the latest source edits was not verified as part of this documentation exercise — do not treat its presence as proof of a passing build for the current changes.

**Browser-based / end-to-end testing:** **Not performed.** This repository has no browser-driven E2E test harness. The closest equivalent used in this work is "production-flow" testing — exercising real service objects with only their repository dependencies mocked — which is not the same as a real browser/user-driven end-to-end test.

**Known test limitations:**
- Intermediate test-count milestones are not independently verifiable (see above).
- `RegularizationServiceTest`'s pre-existing "today" fixtures carry a disclosed, unresolved time-of-day fragility around the new 07:00 AM boundary (see Bugs and Issues Fixed).

---

# Deployment / Commit Status

| Feature | Latest Commit | Latest Uncommitted Work | Production/Deployment Status |
|---|---|---|---|
| Request Regularization | `39d7e80` (12-Aug-2026) | Notifications, 07:00 AM boundary, Access-Denied investigation — **uncommitted as of 13-Aug-2026** | Core workflow (through 12-Aug-2026) is committed and merged to `dev`/`main`. The 13-Aug-2026 work has not been committed, reviewed, or deployed. |
| Cancel Penalties | `39d7e80` (12-Aug-2026) | None | Fully committed; no outstanding uncommitted changes to this feature's own code. |
| Team Punctuality | `7367bb5` (12-Aug-2026, mobile/tablet responsiveness) | None | Fully committed; no outstanding uncommitted changes. |
| Penalization Policy | `39d7e80` (12-Aug-2026) | Phase 1, Phase 2, Deep Verification audit, tier/week-off test follow-up — **uncommitted as of 13-Aug-2026** | Original single-global-policy engine (through 12-Aug-2026) is committed and merged. All multi-policy work is uncommitted, not reviewed, and not deployed. |

**Do not interpret any 13-Aug-2026 item in this document as merged or deployed.** It exists only in the local working tree at the time of writing (52 changed files total, confirmed via `git status --porcelain`).

---
---

# Technical Appendix

*The sections below preserve implementation-level detail from the prior version of this document, for engineering reference. Management readers do not need to read past this point.*

## A.1 Database Migration History

| Migration | Date (committed) | Feature | Notes |
|---|---|---|---|
| `V17__create_attendance_and_regularization.sql` | 31-Jul-2026 (`3629a89`) | Request Regularization | Originally authored as V12; renumbered. |
| `V40__extend_regularization_requests.sql` | 03-Aug-2026 (`b4668de`) | Request Regularization | Two-stage approval support |
| `V41__create_regularization_approvals.sql` | 03-Aug-2026 (`b4668de`) | Request Regularization | `RegularizationApproval` table |
| `V47__enforce_one_approved_regularization_per_date.sql` | 04-Aug-2026 (`dbb0c91`) | Request Regularization | Uniqueness constraint |
| `V51__add_partially_approved_regularization_status.sql` | 05-Aug-2026 (`9c9c58b`) | Request Regularization | New status |
| `V52__add_regularization_stage_approval_columns.sql` | 05-Aug-2026 (`9c9c58b`) | Request Regularization | Stage tracking |
| `V53__add_actor_role_to_regularization_approvals.sql` | 05-Aug-2026 (`9c9c58b`) | Request Regularization | Actor-role tracking |
| `V95__create_shift_weeklyoff_penalisation_tables.sql` | 10-Aug-2026 (`0bf94a4`/`891e0c1`) | Penalization Policy | Assignment layer (ONEHR-108) |
| `V103__create_attendance_penalties.sql` | 12-Aug-2026 (`39d7e80`) | Cancel Penalties | Originally authored as V98; renumbered |
| `V104__create_penalization_policy_versions.sql` | 12-Aug-2026 (`39d7e80`) | Penalization Policy | Originally authored as V99; renumbered |
| `V105__add_deduction_days_to_attendance_penalties.sql` | 12-Aug-2026 (`39d7e80`) | Cancel Penalties, Penalization Policy | Originally authored as V100; renumbered |
| `V106__penalization_policy_phase1_enhancements.sql` | **13-Aug-2026, uncommitted** | Penalization Policy | Version↔Policy FK link, Basic Info fields, widened cycles, notice-period/deduction-outcome columns |
| `V107__penalization_policy_phase2_enhancements.sql` | **13-Aug-2026, uncommitted** | Penalization Policy | Late Arrival Total-Hours fields, `penalization_policy_late_hours_tiers` table, holiday/week-off adjoining fields |

**Backward-compatibility note:** V106's FK link between policy version and policy is additive and resolves safely for pre-existing single-policy installations via a default-policy fallback — no destructive schema change or required backfill. V107's new tier table and columns are additive with defaults.

## A.2 API Change History

| Date | Endpoint(s) | Change |
|---|---|---|
| 31-Jul-2026 (`3629a89`) | `POST /attendance/regularization`, `GET /attendance/regularization/mine` | Original regularization submission/listing |
| 03-Aug-2026 (`b4668de`) | `GET /attendance/regularization/approvers`, approve/reject endpoints | Two-stage approval endpoints |
| 05-Aug-2026 (`9c9c58b`) | `POST /attendance/regularization/bulk-approve`, `.../bulk-reject` | Bulk actions |
| 12-Aug-2026 (`39d7e80`) | `GET /attendance/team-punctuality` | Team Punctuality dashboard endpoint |
| 12-Aug-2026 (`39d7e80`) | `GET /attendance/penalties`, `POST /attendance/penalties/cancel` | Cancel Penalties endpoints |
| 12-Aug-2026 (`39d7e80`) | Penalization Policy config API (`PenalizationPolicyController`) | Original single-policy config API |
| **13-Aug-2026 (uncommitted)** | `PenalisationPolicyManagementController` — create/rename/clone/list policies | New multi-policy CRUD API |
| **13-Aug-2026 (uncommitted)** | *(no new HTTP surface)* | Notification dispatch is internal; existing `GET /notifications` surfaces the new notification types automatically |

## A.3 UI Change History

| Date | File | Change |
|---|---|---|
| 31-Jul-2026 → 06-Aug-2026 | `AttendancePage.tsx`, `SuperAdminRegularizationPage.tsx` | Regularization submission, approval, bulk-action UI built up incrementally |
| 07-Aug-2026 (`21900f9`) | `MyTeamPage.tsx` | Original My Team view |
| 12-Aug-2026 (`39d7e80`) | `MyTeamPage.tsx` | Team Punctuality dashboard + Cancel Penalties UI added (+427 lines) |
| 12-Aug-2026 (`39d7e80`) | `PenalizationPolicySection.tsx` | Original Penalization Policy config UI (483 lines) |
| 12-Aug-2026 (`18a2fa1`) | `MyTeamPage.tsx` | KPI row alignment fix |
| 12-Aug-2026 (`7367bb5`) | `MyTeamPage.tsx` (and others) | Mobile/tablet responsiveness pass |
| **13-Aug-2026 (uncommitted)** | `PolicyListSection.tsx` (new), `OrgSetupPage.tsx` | Multi-policy list/selection UI mounted ahead of the existing config section |
| **13-Aug-2026 (uncommitted)** | *(none)* | No frontend changes needed for regularization notifications — existing generic notification UI already covers new types |

## A.4 Authorization & Roles Matrix

Only permissions directly confirmed by `@PreAuthorize` annotations in the controllers are listed.

| Feature / Action | Employee | Manager | HR Admin | Super Admin |
|---|:---:|:---:|:---:|:---:|
| Regularization — submit, view own | ✅ | ✅ | ✅ | ✅ |
| Regularization — approve/reject, bulk approve/reject, view history/pending/for-approver | ❌ | ✅ | ✅ | ✅ |
| Regularization — view **all** (`/regularization/all`) | ❌ | ❌ | ❌ | ✅ (Super Admin only) |
| Cancel Penalties — view penalties, cancel | ❌ | ✅ (direct reports only) | ✅ | ✅ |
| Team Punctuality — view dashboard | ❌ | ✅ | ✅ | ✅ |
| Penalization Policy — configure/version (`PenalizationPolicyController`) | ❌ | ❌ | ✅ | ✅ |
| Penalization Policy — multi-policy CRUD (`PenalisationPolicyManagementController`) | ❌ | ❌ | ✅ | ✅ |

## A.5 Notification History

| Date | Feature | Event(s) | Notes |
|---|---|---|---|
| 30-Jul-2026 (`997a4af`) | *(infrastructure)* | — | `Notification` entity, `NotificationService`, `NotificationController` created; later reused by `AssetService`, `DocumentService`, `AttendanceService`, and now `RegularizationService` |
| **13-Aug-2026 (uncommitted)** | Request Regularization | Created, Approved, Rejected | Reuses existing `NotificationService.send(...)`; triggered post-transaction-commit; recipients resolved dynamically per event, not hardcoded or broadcast |

Cancel Penalties, Team Punctuality, and the Penalization Policy configuration screens have **no** notification events wired in the current codebase.

## A.6 Reference — Commit Provenance for Key Facts

| Commit | Date | Message | Author |
|---|---|---|---|
| `997a4af` | 30-Jul-2026 | feat: Employee Profile page + Notifications system | — |
| `3629a89` | 31-Jul-2026 | WIP: attendance correction feature in progress | PraveenGurram7 |
| `a6b27e5` | 31-Jul-2026 | Merge feature/attendance-correction into dev with attendance regularization | — |
| `b4668de` | 03-Aug-2026 | Implement attendance regularization fixes | PraveenGurram7 |
| `dbb0c91` | 04-Aug-2026 | Implement attendance correction feature | PraveenGurram7 |
| `9c9c58b` | 05-Aug-2026 | Enhance attendance regularization workflow | PraveenGurram7 |
| `728f7ec` | 06-Aug-2026 | Update attendance regularization restrictions and approval changes | PraveenGurram7 |
| `3f2dbc3` | 06-Aug-2026 | Audit for superadmin and HR | Aanuj04 |
| `21900f9` | 07-Aug-2026 | Add manager My Team view (ONEHR-72) | — |
| `0bf94a4` / `891e0c1` | 10-Aug-2026 | Add manager attendance analytics, assignments, and reports (ONEHR-106/107/108/109) | abuzarnforce |
| `dfc798a` | 11-Aug-2026 | Night-shift timing, midnight-crossover robustness, and live-UI fixes | anilramtenki |
| `39d7e80` | 12-Aug-2026 | Implement attendance penality and punctuality and penalization policy features | PraveenGurram7 |
| `18a2fa1` | 12-Aug-2026 | ONEHR-73: Add peer kudos + KPI row alignment fix | — |
| `7367bb5` | 12-Aug-2026 | mobile and tablet implementation | — |
| `ccd3833` | 12-Aug-2026 | Merge attendance correction into dev | — |
| `7829370` | 12-Aug-2026 | Merge pull request #17 from NForce-One/feature/hr-helpdesk | — (HEAD at time of writing) |
