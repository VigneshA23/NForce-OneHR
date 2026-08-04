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

export interface Punch {
  id: string;
  checkInAt: string;
  checkOutAt: string | null;
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

export interface ApprovalHistoryEntry {
  actionType: 'APPROVED' | 'REJECTED';
  actorName: string;
  comments: string | null;
  actionDate: string;
}

export interface RegularizationRecord {
  id: string;
  employeeUserId: string;
  employeeName: string;
  employeeEmail: string;
  departmentName: string | null;
  attendanceDate: string;
  requestedCheckIn: string | null;
  requestedCheckOut: string | null;
  reason: string;
  status: RegularizationStatus;
  assignedApproverId: string | null;
  assignedApproverName: string | null;
  totalMinutes: number | null;
  reviewedByName: string | null;
  reviewedAt: string | null;
  reviewComment: string | null;
  createdAt: string;
  approvalHistory: ApprovalHistoryEntry[];
}

export interface SubmitRegularizationPayload {
  attendanceDate: string;
  requestedCheckIn?: string;
  requestedCheckOut?: string;
  reason: string;
  managerUserId?: string;
}

export interface ApproverOption {
  userId: string;
  fullName: string;
  email: string;
  // Super Admin is excluded server-side — they have blanket review visibility already and
  // aren't offered as an explicit "Assign To" target.
  roleCode: 'MANAGER' | 'HR_ADMIN';
}

export interface RegularizationFilters {
  employeeUserId?: string;
  approverUserId?: string;
  departmentId?: string;
  month?: string; // 'yyyy-MM'
  status?: RegularizationStatus;
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

  /** Own punch for a single date, or null if the employee never punched that day. */
  punchForDate: (date: string, token: string): Promise<AttendanceRecord | null> =>
    fetch(`${BASE}/attendance/punch/${date}`, { headers: authHeaders(token) })
      .then((res) => (res.status === 204 ? null : handle<AttendanceRecord>(res))),

  /** Every check-in/check-out session for a single day, e.g. to show a lunch-break gap. */
  punches: (date: string, token: string) =>
    fetch(`${BASE}/attendance/punches/${date}`, { headers: authHeaders(token) })
      .then(handle<Punch[]>),
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

  approve: (id: string, token: string, comment?: string) =>
    fetch(`${BASE}/attendance/regularization/${id}/approve`, {
      method: 'PATCH', headers: authHeaders(token),
      body: JSON.stringify({ comment: comment || undefined }),
    }).then(r => handle<RegularizationRecord>(r)),

  reject: (id: string, comment: string, token: string) =>
    fetch(`${BASE}/attendance/regularization/${id}/reject`, {
      method: 'PATCH', headers: authHeaders(token), body: JSON.stringify({ comment }),
    }).then(r => handle<RegularizationRecord>(r)),

  // Only while the request is still PENDING and belongs to the caller (enforced server-side).
  update: (id: string, payload: SubmitRegularizationPayload, token: string) =>
    fetch(`${BASE}/attendance/regularization/${id}`, {
      method: 'PATCH', headers: authHeaders(token), body: JSON.stringify(payload),
    }).then(r => handle<RegularizationRecord>(r)),

  approvers: (token: string) =>
    fetch(`${BASE}/attendance/regularization/approvers`, { headers: authHeaders(token) })
      .then(r => handle<ApproverOption[]>(r)),

  // Super Admin only — full history org-wide, with optional filters.
  all: (filters: RegularizationFilters, token: string) => {
    const params = new URLSearchParams();
    if (filters.employeeUserId) params.set('employeeUserId', filters.employeeUserId);
    if (filters.approverUserId) params.set('approverUserId', filters.approverUserId);
    if (filters.departmentId) params.set('departmentId', filters.departmentId);
    if (filters.month) params.set('month', filters.month);
    if (filters.status) params.set('status', filters.status);
    const qs = params.toString();
    return fetch(`${BASE}/attendance/regularization/all${qs ? `?${qs}` : ''}`, { headers: authHeaders(token) })
      .then(r => handle<RegularizationRecord[]>(r));
  },
};
