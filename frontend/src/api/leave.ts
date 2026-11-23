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

export interface LeaveType {
  id: string;
  code: string;
  name: string;
  classification: LeaveTypeClassification;
  entitlementBasis: EntitlementBasis;
  active: boolean;
  // Filled only by the Leave Configuration listing (leaveConfigApi.listTypes).
  occurrenceWindowMonths?: number | null;
  currentEntitlementDays?: number | null;
  carryForwardEnabled?: boolean;
  carryForwardMaxDays?: number | null;
  carryForwardExpiryMonths?: number | null;
  carryForwardPriority?: CarryForwardPriority;
  /** Records still using the type: above zero means deactivate, not delete. */
  referenceCount?: number;
}

export interface CreateLeaveTypePayload {
  code: string;
  name: string;
  classification: LeaveTypeClassification;
  entitlementBasis?: 'ANNUAL' | 'PER_OCCURRENCE';
  entitlementDays?: number;
  occurrenceWindowMonths?: number;
}

export interface UpdateLeaveTypePayload {
  name?: string;
  classification?: LeaveTypeClassification;
  entitlementBasis?: 'ANNUAL' | 'PER_OCCURRENCE';
  occurrenceWindowMonths?: number;
  /** Only used when an unpaid type becomes paid and has no entitlement yet. */
  entitlementDays?: number;
}

export interface LeaveBalance {
  /** Set by listTeamBalances and the admin view — null for the caller's own balances. */
  employeeUserId?: string | null;
  /** Admin view: the row a manual adjustment targets (regular paid types only). */
  balanceId?: string | null;
  leaveTypeId?: string;
  leaveTypeCode: string;
  leaveTypeName: string;
  classification?: LeaveTypeClassification;
  entitlementBasis?: EntitlementBasis;
  /** Unpaid leave: no limit — the numeric fields below are null. */
  unlimited?: boolean;
  year: number;
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
  leaveTypeClassification: LeaveTypeClassification;
  startDate: string;
  endDate: string;
  halfDay: boolean;
  halfDaySession: 'FIRST_HALF' | 'SECOND_HALF' | null;
  totalDays: number;
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
  reason: string;
}

export const leaveApi = {
  /** Active leave types only — what a new request may use. */
  listTypes: (token: string) =>
    fetch(`${BASE}/types`, { headers: authHeaders(token) }).then(handle<LeaveType[]>),

  listBalances: (token: string) =>
    fetch(`${BASE}/balances`, { headers: authHeaders(token) }).then(handle<LeaveBalance[]>),

  /** Current-year balances for the caller's direct reports (each row tagged with employeeUserId). */
  listTeamBalances: (token: string) =>
    fetch(`${BASE}/balances/team`, { headers: authHeaders(token) }).then(handle<LeaveBalance[]>),

  submit: (payload: SubmitLeaveRequestPayload, token: string) =>
    fetch(`${BASE}/requests`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<LeaveRequestRecord>),

  /** Edit a still-pending request — own requests only, PENDING status only (server-enforced). */
  update: (id: string, payload: SubmitLeaveRequestPayload, token: string) =>
    fetch(`${BASE}/requests/${id}`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<LeaveRequestRecord>),

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

export interface EntitlementPolicy {
  id: string;
  effectiveYear: number;
  days: number;
  createdByName: string;
  createdAt: string;
}

export interface CarryForwardSettings {
  enabled: boolean;
  maxDays: number | null;
  expiryMonths: number | null;
  priority: CarryForwardPriority;
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

  listEntitlements: (id: string, token: string) => send<EntitlementPolicy[]>('GET', `${CONFIG}/types/${id}/entitlements`, token),
  changeEntitlement: (id: string, payload: { effectiveYear: number; days: number; applyToExistingBalances: boolean }, token: string) =>
    send<EntitlementPolicy[]>('POST', `${CONFIG}/types/${id}/entitlements`, token, payload),

  updateCarryForward: (id: string, payload: CarryForwardSettings, token: string) =>
    send<LeaveType>('PUT', `${CONFIG}/types/${id}/carry-forward`, token, payload),
  listTransfers: (fromYear: number, token: string) =>
    send<CarryForwardTransfer[]>('GET', `${CONFIG}/carry-forward/transfers?fromYear=${fromYear}`, token),
  runYearEnd: (fromYear: number, token: string) =>
    send<YearEndRunResult>('POST', `${CONFIG}/carry-forward/run?fromYear=${fromYear}`, token),

  employees: (token: string) => send<DirectoryEmployee[]>('GET', `${CONFIG}/employees`, token),
  employeeBalances: (userId: string, year: number, token: string) =>
    send<LeaveBalance[]>('GET', `${CONFIG}/employees/${userId}/balances?year=${year}`, token),
  adjustBalance: (balanceId: string, payload: { entitlementDays: number; carriedForwardDays: number; reason: string }, token: string) =>
    send<LeaveBalance>('PATCH', `${CONFIG}/balances/${balanceId}`, token, payload),
};
