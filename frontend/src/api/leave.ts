import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/leave`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error((body as any).message ?? `Request failed (${res.status})`);
  return body as T;
}

export type LeaveTypeClassification = 'PAID' | 'UNPAID';

/** ANNUAL = a yearly balance; PER_OCCURRENCE = e.g. Maternity, per eligible occurrence; UNLIMITED = unpaid. */
export type EntitlementBasis = 'ANNUAL' | 'PER_OCCURRENCE' | 'UNLIMITED';
export type CarryForwardPriority = 'CARRY_FORWARD_FIRST' | 'CURRENT_FIRST';
/** YEARLY = the whole quota at once on 1 January; MONTHLY = quota / 12 on the 1st of each month. */
export type CreditFrequency = 'YEARLY' | 'MONTHLY';

/** Who a leave type applies to. Empty lists = no restriction of that kind. */
export interface EligibilityConfig {
  genders: string[];
  departmentIds: string[];
  locationIds: string[];
  employmentTypes: string[];
  includeEmployeeIds: string[];
  excludeEmployeeIds: string[];
  /** LV-06: e.g. "Married" (as on the employee profile). */
  maritalStatuses?: string[];
  /** LV-06: the grade of the employee's designation. */
  grades?: string[];
  /** LV-06: months of service needed, checked on the leave's start date; null = none. */
  minTenureMonths?: number | null;
  /** LV-06: false = not while on probation (credits held until then). Missing = true. */
  allowedDuringProbation?: boolean;
}

export interface LeaveType {
  id: string;
  code: string;
  name: string;
  classification: LeaveTypeClassification;
  entitlementBasis: EntitlementBasis;
  active: boolean;
  /** PAID / UNPAID for the two built-in types: can be renamed, never deleted, deactivated or reclassified. */
  systemKey?: 'PAID' | 'UNPAID' | null;
  // Filled only by the Leave Configuration listing (leaveConfigApi.listTypes).
  occurrenceWindowMonths?: number | null;
  /** LV-01: the leave plans that include this type (quotas and rules live there); empty = nobody can use it. */
  planNames?: string[];
  /** Records still using the type: above zero means deactivate, not delete. */
  referenceCount?: number;
  /** LV-02, listMyTypes only: the caller's plan rules for this type (null = no limits). */
  rules?: LeaveRuleSummary | null;
  /** LV-06, listMyTypes only: held back today by tenure or probation — the first day it can be taken (null = unknown). */
  availableFrom?: string | null;
  /** LV-06: why it is held back. */
  availabilityNote?: string | null;
}

/** LV-02: QUOTA = the plan's days; UNLIMITED = paid, nothing deducted; EARNED_ONLY = only what HR credits. */
export type EntitlementMode = 'QUOTA' | 'UNLIMITED' | 'EARNED_ONLY';

/** LV-02: a type's rules as the apply form shows them. Null numbers = no limit. */
export interface LeaveRuleSummary {
  entitlementMode: EntitlementMode;
  minDaysPerRequest: number | null;
  maxDaysPerRequest: number | null;
  maxRequestsPerMonth: number | null;
  maxRequestsPerYear: number | null;
  maxConsecutiveDays: number | null;
  noticeDays: number | null;
  noticeAppliesOverDays: number | null;
  allowHalfDay: boolean;
  allowHourly: boolean;
  minHours: number | null;
  attachmentRequiredOverDays: number | null;
  /** Lower-case extensions, e.g. ["pdf", "jpg"]; empty = any file. */
  attachmentAllowedTypes: string[];
  attachmentMaxMb: number | null;
  /** Leave types that can't be taken directly before or after this one. */
  notNextTo: string[];
  hoursPerDay: number;
  /** LV-07: weekly offs / holidays between leave days count as leave. */
  sandwichEnabled: boolean;
  sandwichAppliesTo: SandwichAppliesTo;
  sandwichMinDays: number | null;
  sandwichAcrossRequests: boolean;
}

export type SandwichAppliesTo = 'HOLIDAYS' | 'WEEK_OFFS' | 'BOTH';

/** LV-07: what an unsaved request would take (POST /requests/preview). Only `problem` is set when the dates can't be counted. */
export interface LeavePreview {
  totalDays: number | null;
  workingDays: number | null;
  sandwichedDays: number | null;
  /** "4 days: 2 working + 2 sandwiched" */
  summary: string | null;
  daysByYear: Record<string, number>;
  days: { date: string; kind: 'WORKING' | 'SANDWICHED' | 'WEEKLY_OFF' | 'HOLIDAY'; days: number; note: string | null }[];
  /** The first reason it would be refused (dates, a plan rule, the balance), else null. */
  problem: string | null;
}

export interface CreateLeaveTypePayload {
  code: string;
  name: string;
  classification: LeaveTypeClassification;
  entitlementBasis?: 'ANNUAL' | 'PER_OCCURRENCE';
  occurrenceWindowMonths?: number;
}

export interface UpdateLeaveTypePayload {
  name?: string;
  /** Only while nothing uses the type yet. */
  classification?: LeaveTypeClassification;
  entitlementBasis?: 'ANNUAL' | 'PER_OCCURRENCE';
  occurrenceWindowMonths?: number;
}

export interface LeaveBalance {
  /** Set by listTeamBalances and the admin view — null for the caller's own balances. */
  employeeUserId?: string | null;
  /** Admin view: the row a manual adjustment targets (regular paid types only). */
  balanceId?: string | null;
  leaveTypeId?: string;
  leaveTypeCode: string;
  leaveTypeName: string;
  /** False for a deactivated type (listed in the admin view only). */
  leaveTypeActive?: boolean;
  classification?: LeaveTypeClassification;
  entitlementBasis?: EntitlementBasis;
  /** Unpaid leave: no limit — the numeric fields below are null. */
  unlimited?: boolean;
  year: number;
  /** The leave year hasn't started: figures are what is expected (annualQuotaDays), nothing is credited yet. */
  expected?: boolean;
  totalDays: number | null;
  usedDays: number | null;
  pendingDays?: number | null;
  remainingDays: number | null;
  carriedForwardDays?: number | null;
  carriedForwardUsedDays?: number | null;
  carriedForwardExpiredDays?: number | null;
  carriedForwardRemainingDays?: number | null;
  carriedForwardExpiresOn?: string | null;
  occurrenceStartDate?: string | null;
  occurrenceWindowEndDate?: string | null;
  // Crediting (regular paid types). totalDays = credited so far; remainingDays can go negative.
  creditFrequency?: CreditFrequency | null;
  /** This year's full quota for this employee: credited + still to come. */
  annualQuotaDays?: number | null;
  upcomingCreditDays?: number | null;
  /** What a new request may use, including credits still to come this year (leave in advance). */
  availableToApplyDays?: number | null;
  nextCreditDate?: string | null;
  nextCreditDays?: number | null;
}

export interface LeaveRequestRecord {
  id: string;
  employeeUserId: string;
  employeeName: string;
  /** Null for a User with no Employee row (auth-only account) — same fallback as employeeName's
   * own "Unknown", just displayed as absent rather than a placeholder string. */
  employeeCode: string | null;
  leaveTypeCode: string;
  leaveTypeName: string;
  /** The type before every type was merged into Paid / Unpaid (e.g. "Sick Leave"); null otherwise. */
  originalLeaveTypeName?: string | null;
  leaveTypeClassification: LeaveTypeClassification;
  startDate: string;
  endDate: string;
  halfDay: boolean;
  halfDaySession: 'FIRST_HALF' | 'SECOND_HALF' | null;
  totalDays: number;
  /** LV-07: weekly offs / holidays included in totalDays by the sandwich rule. */
  sandwichedDays?: number | null;
  durationType?: 'FULL_DAY' | 'HALF_DAY' | 'HOURLY' | 'QUARTER_DAY';
  /** LV-02: hours of an hourly request. */
  hours?: number | null;
  /** LV-02: the attached file's name (download with leaveApi.downloadAttachment); null = none. */
  attachmentName?: string | null;
  /** Leave year -> days, only for a request spanning two leave years (each part uses its own year's balance). */
  daysByYear?: Record<string, number> | null;
  /** PENALIZED = read-only row synthesized from an attendance penalty's leave deduction (own requests only). */
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'PENALIZED';
  employeeReason: string;
  decisionReason: string | null;
  decidedByName: string | null;
  decidedAt: string | null;
  createdAt: string;
}

export interface SubmitLeaveRequestPayload {
  leaveTypeCode: string;
  startDate: string;
  endDate: string;
  halfDay: boolean;
  halfDaySession?: 'FIRST_HALF' | 'SECOND_HALF';
  /** LV-02: hours on a single day (hourly leave); omit for a full or half day. */
  hours?: number | null;
  reason: string;
}

/** JSON alone, or multipart ("request" JSON part + "attachment" file) when there is a file. */
function requestInit(method: string, payload: SubmitLeaveRequestPayload, token: string, file?: File | null): RequestInit {
  if (!file) return { method, headers: authHeaders(token), body: JSON.stringify(payload) };
  const form = new FormData();
  form.append('request', new Blob([JSON.stringify(payload)], { type: 'application/json' }));
  form.append('attachment', file);
  return { method, headers: { Authorization: `Bearer ${token}` }, body: form };
}

export const leaveApi = {
  /** Active leave types only (all of them, for admin pickers). */
  listTypes: (token: string) =>
    fetch(`${BASE}/types`, { headers: authHeaders(token) }).then(handle<LeaveType[]>),

  /** The active types the caller may apply for (their "applies to" rules) — the request form. */
  listMyTypes: (token: string) =>
    fetch(`${BASE}/types/mine`, { headers: authHeaders(token) }).then(handle<LeaveType[]>),

  /** The caller's balances for the current leave year, or `year` = the next one (a read-only "expected" view). */
  listBalances: (token: string, year?: number) =>
    fetch(`${BASE}/balances${year != null ? `?year=${year}` : ''}`, { headers: authHeaders(token) }).then(handle<LeaveBalance[]>),

  /** Balances for the caller's direct reports (each row tagged with employeeUserId), current or next leave year. */
  listTeamBalances: (token: string, year?: number) =>
    fetch(`${BASE}/balances/team${year != null ? `?year=${year}` : ''}`, { headers: authHeaders(token) }).then(handle<LeaveBalance[]>),

  submit: (payload: SubmitLeaveRequestPayload, token: string, file?: File | null) =>
    fetch(`${BASE}/requests`, requestInit('POST', payload, token, file)).then(handle<LeaveRequestRecord>),

  /** Edit a still-pending request — own requests only, PENDING status only (server-enforced). A file replaces the attachment. */
  update: (id: string, payload: SubmitLeaveRequestPayload, token: string, file?: File | null) =>
    fetch(`${BASE}/requests/${id}`, requestInit('PATCH', payload, token, file)).then(handle<LeaveRequestRecord>),

  /** LV-07: the day count and first problem of an unsaved request; `editingId` = the own pending request being edited. */
  preview: (payload: Omit<SubmitLeaveRequestPayload, 'reason'> & { reason?: string }, token: string, editingId?: string) =>
    fetch(`${BASE}/requests/preview${editingId ? `?editing=${editingId}` : ''}`, {
      method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload),
    }).then(handle<LeavePreview>),

  /** LV-02: the request's attachment as a file download (the requester, or whoever may decide it). */
  downloadAttachment: async (id: string, fileName: string, token: string) => {
    const res = await fetch(`${BASE}/requests/${id}/attachment`, { headers: { Authorization: `Bearer ${token}` } });
    if (!res.ok) await handle(res);
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    a.click();
    URL.revokeObjectURL(url);
  },

  listMine: (token: string) =>
    fetch(`${BASE}/requests/mine`, { headers: authHeaders(token) }).then(handle<LeaveRequestRecord[]>),

  listApprovals: (token: string) =>
    fetch(`${BASE}/approvals`, { headers: authHeaders(token) }).then(handle<LeaveRequestRecord[]>),

  /** Approved leave for the manager's direct reports overlapping [from, to]. */
  team: (from: string, to: string, token: string) =>
    fetch(`${BASE}/team?from=${from}&to=${to}`, { headers: authHeaders(token) }).then(handle<LeaveRequestRecord[]>),

  /** Approved leave org-wide overlapping [from, to] — HR/Super Admin's On Leave KPI. */
  organization: (from: string, to: string, token: string) =>
    fetch(`${BASE}/organization?from=${from}&to=${to}`, { headers: authHeaders(token) }).then(handle<LeaveRequestRecord[]>),

  /** Approved leave for the caller's current peers overlapping [from, to] — Peers view (ONEHR-73). */
  peers: (from: string, to: string, token: string) =>
    fetch(`${BASE}/peers?from=${from}&to=${to}`, { headers: authHeaders(token) }).then(handle<LeaveRequestRecord[]>),

  approve: (id: string, token: string) =>
    fetch(`${BASE}/requests/${id}/approve`, { method: 'POST', headers: authHeaders(token) }).then(handle<LeaveRequestRecord>),

  reject: (id: string, reason: string, token: string) =>
    fetch(`${BASE}/requests/${id}/reject`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ reason }) }).then(handle<LeaveRequestRecord>),
};

// ── Leave Configuration (Organization Masters > Leave) — every call needs leave_configuration ──

const CONFIG = `${API_ORIGIN}/api/leave-config`;

// ── LV-01 Leave Plans ──

export interface LeavePlanSummary {
  id: string;
  name: string;
  description: string | null;
  leaveYearStartMonth: number;
  status: 'ACTIVE' | 'INACTIVE';
  orgDefault: boolean;
  currentVersion: number | null;
  currentVersionFrom: string | null;
  leaveTypeCount: number;
  /** Employees on the plan today (allocation, rule or default). */
  employeeCount: number;
  updatedAt: string;
}

export interface LeavePlanVersionSummary {
  id: string;
  version: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  status: 'PAST' | 'CURRENT' | 'SCHEDULED';
  createdByName: string;
  createdAt: string;
}

export interface LeavePlanDetail {
  plan: LeavePlanSummary;
  versions: LeavePlanVersionSummary[];
}

/** One leave type inside a plan version. */
export interface PlanTypeRule {
  leaveTypeId: string;
  leaveTypeCode: string;
  leaveTypeName: string;
  classification: LeaveTypeClassification;
  entitlementBasis: EntitlementBasis;
  leaveTypeActive: boolean;
  days: number | null;
  creditFrequency: CreditFrequency | null;
  carryForwardEnabled: boolean;
  carryForwardMaxDays: number | null;
  carryForwardExpiryMonths: number | null;
  carryForwardPriority: CarryForwardPriority;
  eligibility: EligibilityConfig;
  limits: PlanRuleLimits;
}

/** LV-02: how a type may be requested under a plan. Null numbers = no limit. */
export interface PlanRuleLimits {
  entitlementMode: EntitlementMode;
  minDaysPerRequest: number | null;
  maxDaysPerRequest: number | null;
  maxRequestsPerMonth: number | null;
  maxRequestsPerYear: number | null;
  maxConsecutiveDays: number | null;
  noticeDays: number | null;
  noticeAppliesOverDays: number | null;
  allowHalfDay: boolean;
  allowHourly: boolean;
  minHours: number | null;
  attachmentRequiredOverDays: number | null;
  /** Comma-separated extensions, e.g. "pdf,jpg"; blank = any. */
  attachmentAllowedTypes: string | null;
  attachmentMaxMb: number | null;
  /** Leave type ids that can't be taken directly before or after this type. */
  notNextToTypeIds: string[];
  /** LV-07 sandwich rule. */
  sandwichEnabled: boolean;
  sandwichAppliesTo: SandwichAppliesTo;
  sandwichMinDays: number | null;
  sandwichAcrossRequests: boolean;
}

export interface LeavePlanVersionDetail {
  planId: string;
  planName: string;
  version: LeavePlanVersionSummary;
  rules: PlanTypeRule[];
}

/** What the plan editor sends for one leave type. */
export interface PlanTypeRulePayload {
  leaveTypeId: string;
  days?: number | null;
  creditFrequency?: CreditFrequency | null;
  carryForwardEnabled: boolean;
  carryForwardMaxDays?: number | null;
  carryForwardExpiryMonths?: number | null;
  carryForwardPriority?: CarryForwardPriority;
  eligibility?: EligibilityConfig;
  limits?: PlanRuleLimits;
}

export type PlanSource = 'ALLOCATION' | 'RULE' | 'DEFAULT';

export interface PlanAllocation {
  id: string;
  employeeUserId: string;
  planId: string;
  planName: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  status: 'CURRENT' | 'FUTURE' | 'HISTORICAL';
  createdAt: string;
}

export interface EmployeePlanRow {
  employeeUserId: string;
  employeeCode: string | null;
  fullName: string;
  active: boolean;
  departmentId: string | null;
  departmentName: string | null;
  locationId: string | null;
  locationName: string | null;
  planId: string | null;
  planName: string | null;
  source: PlanSource | null;
  ruleName: string | null;
  currentAllocation: PlanAllocation | null;
  upcomingAllocation: PlanAllocation | null;
}

export interface BulkResult {
  succeededIds: string[];
  failed: { employeeUserId: string; reason: string }[];
}

export interface CarryForwardTransfer {
  id: string;
  employeeUserId: string;
  employeeName: string;
  employeeCode: string | null;
  leaveTypeCode: string;
  leaveTypeName: string;
  fromYear: number;
  unusedDays: number;
  carriedDays: number;
  lapsedDays: number;
  expiresOn: string | null;
  processedAt: string;
  updatedAt: string;
}

export interface YearEndRunResult {
  fromYear: number;
  balancesChecked: number;
  transfersCreated: number;
  expiriesUpdated: number;
  failures: number;
  /** Codes of the employees that failed (retried on the next run). */
  failedEmployees?: string[];
}

export interface AccrualRunResult {
  year: number;
  employeesChecked: number;
  creditsAdded: number;
  failures: number;
  /** Codes of the employees that failed (retried on the next run). */
  failedEmployees?: string[];
}

/** YE-02: one employee x leave type row of the opening-balance template. */
export interface OpeningBalanceTemplateRow {
  employeeCode: string;
  employeeName: string;
  leaveType: string;
  usedDays: number;
  currentCredited: number;
  currentCarriedForward: number;
}

/** One uploaded row; the new values are the cells as typed (blank = unchanged). */
export interface OpeningBalanceUploadRow {
  rowNumber: number;
  employeeCode: string;
  leaveType: string;
  newCredited: string | null;
  newCarriedForward: string | null;
  reason: string | null;
}

export interface OpeningBalanceRowResult {
  rowNumber: number;
  employeeCode: string;
  employeeName: string | null;
  leaveType: string;
  currentCredited: number;
  newCredited: number;
  currentCarriedForward: number;
  newCarriedForward: number;
  changed: boolean;
  errors: string[];
  warnings: string[];
}

export interface OpeningBalanceValidation {
  year: number;
  rows: number;
  changes: number;
  errorRows: number;
  results: OpeningBalanceRowResult[];
}

/** YE-07: the last run of a nightly leave job — when, by whom (null = the scheduler) and its counts. */
export interface LeaveJobRun {
  job: string;
  ranAt: string;
  actorId: string | null;
  actorName: string | null;
  details: Record<string, unknown> & { failures?: number; failedEmployees?: string[] };
}

export interface AccrualCredit {
  year: number;
  month: number;
  days: number;
  /** ACCRUAL = a real credit; MIGRATION = a month covered by a balance from before crediting existed. */
  source: 'ACCRUAL' | 'MIGRATION';
  creditedAt: string;
}

export interface DirectoryEmployee {
  userId: string;
  name: string;
  employeeCode: string | null;
}

function send<T>(method: string, url: string, token: string, body?: unknown): Promise<T> {
  return fetch(url, { method, headers: authHeaders(token), body: body === undefined ? undefined : JSON.stringify(body) }).then(handle<T>);
}

export const leaveConfigApi = {
  listTypes: (token: string) => send<LeaveType[]>('GET', `${CONFIG}/types`, token),
  createType: (payload: CreateLeaveTypePayload, token: string) => send<LeaveType>('POST', `${CONFIG}/types`, token, payload),
  updateType: (id: string, payload: UpdateLeaveTypePayload, token: string) => send<LeaveType>('PATCH', `${CONFIG}/types/${id}`, token, payload),
  setActive: (id: string, active: boolean, token: string) => send<LeaveType>('PATCH', `${CONFIG}/types/${id}/active`, token, { active }),
  deleteType: (id: string, token: string) => send<void>('DELETE', `${CONFIG}/types/${id}`, token),

  previewEligibility: (config: EligibilityConfig, token: string) =>
    send<{ eligibleCount: number; totalCount: number }>('POST', `${CONFIG}/types/eligibility-preview`, token, config),

  runAccrual: (token: string) => send<AccrualRunResult>('POST', `${CONFIG}/accrual/run`, token),
  /** Last run of the crediting ("accrual") and year-end ("yearEnd") jobs; a job that never ran is absent. */
  jobs: (token: string) => send<Partial<Record<'accrual' | 'yearEnd', LeaveJobRun>>>('GET', `${CONFIG}/jobs`, token),
  creditHistory: (balanceId: string, token: string) => send<AccrualCredit[]>('GET', `${CONFIG}/balances/${balanceId}/credits`, token),

  listTransfers: (fromYear: number, token: string) =>
    send<CarryForwardTransfer[]>('GET', `${CONFIG}/carry-forward/transfers?fromYear=${fromYear}`, token),
  runYearEnd: (fromYear: number, token: string) =>
    send<YearEndRunResult>('POST', `${CONFIG}/carry-forward/run?fromYear=${fromYear}`, token),

  /** YE-02: opening-balance template rows (the browser builds the .xlsx), row-level validation and apply. */
  openingBalanceTemplate: (year: number, token: string) =>
    send<OpeningBalanceTemplateRow[]>('GET', `${CONFIG}/opening-balances?year=${year}`, token),
  validateOpeningBalances: (year: number, rows: OpeningBalanceUploadRow[], token: string) =>
    send<OpeningBalanceValidation>('POST', `${CONFIG}/opening-balances/validate?year=${year}`, token, rows),
  applyOpeningBalances: (year: number, rows: OpeningBalanceUploadRow[], token: string) =>
    send<{ year: number; changes: number }>('POST', `${CONFIG}/opening-balances/apply?year=${year}`, token, rows),

  employees: (token: string) => send<DirectoryEmployee[]>('GET', `${CONFIG}/employees`, token),
  employeeBalances: (userId: string, year: number, token: string) =>
    send<LeaveBalance[]>('GET', `${CONFIG}/employees/${userId}/balances?year=${year}`, token),
  adjustBalance: (balanceId: string, payload: { entitlementDays: number; carriedForwardDays: number; reason: string }, token: string) =>
    send<LeaveBalance>('PATCH', `${CONFIG}/balances/${balanceId}`, token, payload),

  // ── LV-01 Leave Plans ──
  plans: (token: string) => send<LeavePlanSummary[]>('GET', `${CONFIG}/plans`, token),
  plan: (id: string, token: string) => send<LeavePlanDetail>('GET', `${CONFIG}/plans/${id}`, token),
  currentPlanVersion: (id: string, token: string) => send<LeavePlanVersionDetail>('GET', `${CONFIG}/plans/${id}/current-version`, token),
  planVersion: (versionId: string, token: string) => send<LeavePlanVersionDetail>('GET', `${CONFIG}/plan-versions/${versionId}`, token),
  createPlan: (payload: { name: string; description?: string; rules: PlanTypeRulePayload[] }, token: string) =>
    send<LeavePlanDetail>('POST', `${CONFIG}/plans`, token, payload),
  updatePlan: (id: string, payload: { name: string; description?: string }, token: string) =>
    send<LeavePlanDetail>('PATCH', `${CONFIG}/plans/${id}`, token, payload),
  savePlanVersion: (id: string, payload: { effectiveFrom: string; rules: PlanTypeRulePayload[]; recreditCredited: boolean }, token: string) =>
    send<LeavePlanDetail>('POST', `${CONFIG}/plans/${id}/versions`, token, payload),
  clonePlan: (id: string, name: string, token: string) => send<LeavePlanDetail>('POST', `${CONFIG}/plans/${id}/clone`, token, { name }),
  setPlanActive: (id: string, active: boolean, token: string) => send<LeavePlanDetail>('PATCH', `${CONFIG}/plans/${id}/active`, token, { active }),
  setDefaultPlan: (id: string, token: string) => send<LeavePlanDetail>('PATCH', `${CONFIG}/plans/${id}/default`, token),
  deletePlan: (id: string, token: string) => send<void>('DELETE', `${CONFIG}/plans/${id}`, token),

  planEmployees: (token: string) => send<EmployeePlanRow[]>('GET', `${CONFIG}/plan-allocations/employees`, token),
  planEmployee: (userId: string, token: string) =>
    send<{ row: EmployeePlanRow; history: PlanAllocation[] }>('GET', `${CONFIG}/plan-allocations/employees/${userId}`, token),
  allocatePlan: (payload: { employeeUserId: string; planId: string; effectiveFrom: string; effectiveTo?: string | null }, token: string) =>
    send<PlanAllocation>('POST', `${CONFIG}/plan-allocations`, token, payload),
  updatePlanAllocation: (id: string, payload: { planId: string; effectiveFrom: string; effectiveTo?: string | null }, token: string) =>
    send<PlanAllocation>('PUT', `${CONFIG}/plan-allocations/${id}`, token, payload),
  removePlanAllocation: (id: string, token: string) => send<void>('DELETE', `${CONFIG}/plan-allocations/${id}`, token),
  bulkAllocatePlan: (payload: { employeeUserIds: string[]; planId: string; effectiveFrom: string; effectiveTo?: string | null }, token: string) =>
    send<BulkResult>('POST', `${CONFIG}/plan-allocations/bulk`, token, payload),
  bulkRemovePlan: (employeeUserIds: string[], token: string) =>
    send<BulkResult>('POST', `${CONFIG}/plan-allocations/bulk-remove`, token, { employeeUserIds }),
  checkPlanConflicts: (payload: { employeeUserIds: string[]; effectiveFrom: string; effectiveTo?: string | null }, token: string) =>
    send<Record<string, PlanAllocation>>('POST', `${CONFIG}/plan-allocations/check-conflicts`, token, payload),
};
