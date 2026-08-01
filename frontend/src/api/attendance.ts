const BASE = '/api';

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error(body.message ?? `Request failed (${res.status})`);
  return body as T;
}

export type AttendanceStatus = 'PRESENT' | 'LATE' | 'HALF_DAY' | 'ABSENT';
export type AttendanceSource = 'SYSTEM' | 'REGULARIZATION';

export interface AttendanceRecord {
  id: string | null;
  employeeUserId: string;
  employeeCode: string;
  fullName: string;
  workDate: string;
  checkInAt: string | null;
  checkOutAt: string | null;
  workedMinutes: number | null;
  status: AttendanceStatus | null;
  lateByMinutes: number | null;
  fullDay: boolean | null;
  source: AttendanceSource | null;
}

export interface TodayAttendance {
  workDate: string;
  /** Current time in the server's business timezone — the clock elapsed time is measured against. */
  serverNow: string;
  canCheckIn: boolean;
  canCheckOut: boolean;
  /** Null until the employee has punched in today. */
  record: AttendanceRecord | null;
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
  today: (token: string) =>
    fetch(`${BASE}/attendance/today`, { headers: authHeaders(token) }).then(handle<TodayAttendance>),

  // No request body — the server generates the timestamp.
  checkIn: (token: string) =>
    fetch(`${BASE}/attendance/check-in`, { method: 'POST', headers: authHeaders(token) })
      .then(handle<AttendanceRecord>),

  checkOut: (token: string) =>
    fetch(`${BASE}/attendance/check-out`, { method: 'POST', headers: authHeaders(token) })
      .then(handle<AttendanceRecord>),

  myHistory: (from: string, to: string, token: string) =>
    fetch(`${BASE}/attendance/me?from=${from}&to=${to}`, { headers: authHeaders(token) })
      .then(handle<AttendanceRecord[]>),

  day: (date: string, token: string) =>
    fetch(`${BASE}/attendance/day?date=${date}`, { headers: authHeaders(token) })
      .then(handle<AttendanceRecord[]>),

  team: (date: string, token: string) =>
    fetch(`${BASE}/attendance/team?date=${date}`, { headers: authHeaders(token) })
      .then(handle<AttendanceRecord[]>),
};

export const regularizationApi = {
  submit: (payload: SubmitRegularizationPayload, token: string) =>
    fetch(`${BASE}/attendance/regularization`, {
      method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload),
    }).then(r => handle<RegularizationRecord>(r)),

  mine: (token: string) =>
    fetch(`${BASE}/attendance/regularization/mine`, { headers: authHeaders(token) }).then(r => handle<RegularizationRecord[]>(r)),

  pending: (token: string) =>
    fetch(`${BASE}/attendance/regularization/pending`, { headers: authHeaders(token) }).then(r => handle<RegularizationRecord[]>(r)),

  approve: (id: string, token: string) =>
    fetch(`${BASE}/attendance/regularization/${id}/approve`, {
      method: 'PATCH', headers: authHeaders(token),
    }).then(r => handle<RegularizationRecord>(r)),

  reject: (id: string, comment: string, token: string) =>
    fetch(`${BASE}/attendance/regularization/${id}/reject`, {
      method: 'PATCH', headers: authHeaders(token), body: JSON.stringify({ comment }),
    }).then(r => handle<RegularizationRecord>(r)),
};
