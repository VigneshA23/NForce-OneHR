const BASE = '/api/attendance';

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error((body as { message?: string }).message ?? `Request failed (${res.status})`);
  return body as T;
}

export interface AttendanceRecord {
  id: string;
  attendanceDate: string;
  checkIn: string | null;
  checkOut: string | null;
  status: string;
  source: string;
}

export type RegularizationStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export interface RegularizationRecord {
  id: string;
  employeeUserId: string;
  employeeName: string;
  employeeEmail: string;
  attendanceDate: string;
  requestedCheckIn: string | null;
  requestedCheckOut: string | null;
  reason: string;
  status: RegularizationStatus;
  reviewedByName: string | null;
  reviewedAt: string | null;
  reviewComment: string | null;
  createdAt: string;
}

export interface SubmitRegularizationPayload {
  attendanceDate: string;
  requestedCheckIn?: string;
  requestedCheckOut?: string;
  reason: string;
}

export const attendanceApi = {
  myRecords: (token: string) =>
    fetch(`${BASE}/me`, { headers: authHeaders(token) }).then(r => handle<AttendanceRecord[]>(r)),
};

export const regularizationApi = {
  submit: (payload: SubmitRegularizationPayload, token: string) =>
    fetch(`${BASE}/regularization`, {
      method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload),
    }).then(r => handle<RegularizationRecord>(r)),

  mine: (token: string) =>
    fetch(`${BASE}/regularization/mine`, { headers: authHeaders(token) }).then(r => handle<RegularizationRecord[]>(r)),

  pending: (token: string) =>
    fetch(`${BASE}/regularization/pending`, { headers: authHeaders(token) }).then(r => handle<RegularizationRecord[]>(r)),

  approve: (id: string, token: string) =>
    fetch(`${BASE}/regularization/${id}/approve`, {
      method: 'PATCH', headers: authHeaders(token),
    }).then(r => handle<RegularizationRecord>(r)),

  reject: (id: string, comment: string, token: string) =>
    fetch(`${BASE}/regularization/${id}/reject`, {
      method: 'PATCH', headers: authHeaders(token), body: JSON.stringify({ comment }),
    }).then(r => handle<RegularizationRecord>(r)),
};
