import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/attendance/overtime`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error(body.message ?? `Request failed (${res.status})`);
  return body as T;
}

export type OvertimeRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export interface OvertimeRequestRecord {
  id: string;
  employeeUserId: string;
  employeeName: string;
  employeeEmail: string;
  departmentName: string | null;
  workDate: string;
  requestedStart: string;
  requestedEnd: string;
  requestedMinutes: number;
  reason: string;
  status: OvertimeRequestStatus;
  assignedApproverId: string | null;
  assignedApproverName: string | null;
  /** A colleague notified about this request — informational only, not an approver. */
  notifyUserId: string | null;
  notifyUserName: string | null;
  reviewedByName: string | null;
  /** The reviewer's role at response time (e.g. "MANAGER", "HR_ADMIN", "SUPER_ADMIN") — HR
   * Admin/Super Admin can decide a manager-stage request too, so this distinguishes who actually
   * acted in what capacity for the "Last Action By" column. */
  reviewedByRole: string | null;
  reviewedAt: string | null;
  reviewComment: string | null;
  createdAt: string;
}

export interface SubmitOvertimeRequestPayload {
  workDate: string;
  requestedStart: string;
  requestedEnd: string;
  reason: string;
  managerUserId?: string;
  notifyUserId?: string;
}

export const overtimeRequestApi = {
  submit: (payload: SubmitOvertimeRequestPayload, token: string) =>
    fetch(BASE, {
      method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload),
    }).then(r => handle<OvertimeRequestRecord>(r)),

  mine: (token: string) =>
    fetch(`${BASE}/mine`, { headers: authHeaders(token) }).then(r => handle<OvertimeRequestRecord[]>(r)),

  pending: (token: string) =>
    fetch(`${BASE}/pending`, { headers: authHeaders(token) }).then(r => handle<OvertimeRequestRecord[]>(r)),

  approve: (id: string, token: string, comment?: string) =>
    fetch(`${BASE}/${id}/approve`, {
      method: 'PATCH', headers: authHeaders(token),
      body: JSON.stringify({ comment: comment || undefined }),
    }).then(r => handle<OvertimeRequestRecord>(r)),

  reject: (id: string, comment: string, token: string) =>
    fetch(`${BASE}/${id}/reject`, {
      method: 'PATCH', headers: authHeaders(token), body: JSON.stringify({ comment }),
    }).then(r => handle<OvertimeRequestRecord>(r)),
};
