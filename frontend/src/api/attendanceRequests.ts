import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/attendance/requests`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error(body.message ?? `Request failed (${res.status})`);
  return body as T;
}

export type AttendanceRequestType = 'WFH' | 'PARTIAL_DAY';
export type AttendanceRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export interface AttendanceRequestRecord {
  id: string;
  employeeUserId: string;
  employeeName: string;
  employeeEmail: string;
  departmentName: string | null;
  requestType: AttendanceRequestType;
  requestDate: string;
  /** Only meaningful for PARTIAL_DAY — null for WFH. */
  partialDayHours: number | null;
  reason: string;
  status: AttendanceRequestStatus;
  assignedApproverId: string | null;
  assignedApproverName: string | null;
  reviewedByName: string | null;
  reviewedAt: string | null;
  reviewComment: string | null;
  createdAt: string;
}

export interface SubmitAttendanceRequestPayload {
  requestType: AttendanceRequestType;
  requestDate: string;
  partialDayHours?: number;
  reason: string;
  managerUserId?: string;
}

export const attendanceRequestApi = {
  submit: (payload: SubmitAttendanceRequestPayload, token: string) =>
    fetch(BASE, {
      method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload),
    }).then(r => handle<AttendanceRequestRecord>(r)),

  mine: (token: string) =>
    fetch(`${BASE}/mine`, { headers: authHeaders(token) }).then(r => handle<AttendanceRequestRecord[]>(r)),

  pending: (token: string) =>
    fetch(`${BASE}/pending`, { headers: authHeaders(token) }).then(r => handle<AttendanceRequestRecord[]>(r)),

  approve: (id: string, token: string, comment?: string) =>
    fetch(`${BASE}/${id}/approve`, {
      method: 'PATCH', headers: authHeaders(token),
      body: JSON.stringify({ comment: comment || undefined }),
    }).then(r => handle<AttendanceRequestRecord>(r)),

  reject: (id: string, comment: string, token: string) =>
    fetch(`${BASE}/${id}/reject`, {
      method: 'PATCH', headers: authHeaders(token), body: JSON.stringify({ comment }),
    }).then(r => handle<AttendanceRequestRecord>(r)),
};
