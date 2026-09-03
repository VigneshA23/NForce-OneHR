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

export interface LeaveType {
  id: string;
  code: string;
  name: string;
}

export interface LeaveBalance {
  leaveTypeCode: string;
  leaveTypeName: string;
  year: number;
  totalDays: number;
  usedDays: number;
  remainingDays: number;
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
  startDate: string;
  endDate: string;
  halfDay: boolean;
  totalDays: number;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
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
  reason: string;
}

export const leaveApi = {
  listTypes: (token: string) =>
    fetch(`${BASE}/types`, { headers: authHeaders(token) }).then(handle<LeaveType[]>),

  listBalances: (token: string) =>
    fetch(`${BASE}/balances`, { headers: authHeaders(token) }).then(handle<LeaveBalance[]>),

  submit: (payload: SubmitLeaveRequestPayload, token: string) =>
    fetch(`${BASE}/requests`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<LeaveRequestRecord>),

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
