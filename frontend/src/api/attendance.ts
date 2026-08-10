import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api`;

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
export type AttendanceSource = 'SYSTEM' | 'REGULARIZATION' | 'WEB_REMOTE';

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
  /** The employee's configured work mode (ONSITE/REMOTE/HYBRID) at query time. */
  workMode: string | null;
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

// PARTIALLY_APPROVED: the Manager stage has approved, awaiting HR/Super Admin final approval.
export type RegularizationStatus = 'PENDING' | 'PARTIALLY_APPROVED' | 'APPROVED' | 'REJECTED';

export interface ApprovalHistoryEntry {
  actionType: 'APPROVED' | 'REJECTED';
  actorName: string;
  /** Authority actually exercised for this action — not necessarily every role the actor holds. */
  actorRole: 'MANAGER' | 'HR_ADMIN' | 'SUPER_ADMIN' | null;
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
  // Stage 1 (manager approval) — null until a MANAGER approves, and null on a Super Admin
  // bypass straight from PENDING to APPROVED.
  approvedByName: string | null;
  approvedAt: string | null;
  // Stage 2 (final approval) — set once the request reaches the terminal APPROVED status.
  finalApprovedByName: string | null;
  finalApprovedAt: string | null;
  createdAt: string;
  approvalHistory: ApprovalHistoryEntry[];
}

export interface BulkActionResult {
  succeededIds: string[];
  failed: { id: string; reason: string }[];
}

/** One row of the Avg. Work Hours Leaderboard (ONEHR-106). */
export interface TeamEffortEntry {
  employeeUserId: string;
  fullName: string;
  designationName: string | null;
  avgHoursPerDay: number;
  hoursWorked: number;
  expectedHours: number;
  activeDays: number;
}

export interface TeamLateArrivalEntry {
  employeeUserId: string;
  fullName: string;
  designationName: string | null;
  lateDays: number;
  activeDays: number;
  latePct: number;
}

export interface TeamDailyCount {
  date: string;
  count: number;
}

export interface TeamLeastHoursEntry {
  employeeUserId: string;
  fullName: string;
  designationName: string | null;
  avgHoursPerDay: number;
  hoursWorked: number;
}

export interface TeamHoursBucket {
  label: string;
  count: number;
  pct: number;
}

export interface TeamFrequentBreaksEntry {
  employeeUserId: string;
  fullName: string;
  designationName: string | null;
  totalBreakHours: number;
  totalBreakCount: number;
  avgBreaksPerDay: number;
}

export interface TeamDailyAverage {
  date: string;
  avgBreaks: number;
}

/** The three Negligence panels: Late Arrivals, Least Hours Worked, Frequent Breaks (ONEHR-107). */
export interface TeamNegligenceResponse {
  lateArrivals: TeamLateArrivalEntry[];
  dailyLateCounts: TeamDailyCount[];
  leastHoursWorked: TeamLeastHoursEntry[];
  hoursHistogram: TeamHoursBucket[];
  frequentBreaks: TeamFrequentBreaksEntry[];
  breaksTrend: TeamDailyAverage[];
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

  /** Attendance rows for the manager's direct reports across a date range — backs the My Team calendar. */
  teamMonth: (from: string, to: string, token: string) =>
    fetch(`${BASE}/attendance/team-month?from=${from}&to=${to}`, { headers: authHeaders(token) })
      .then(handle<AttendanceRecord[]>),

  /** Own punch for a single date, or null if the employee never punched that day. */
  punchForDate: (date: string, token: string): Promise<AttendanceRecord | null> =>
    fetch(`${BASE}/attendance/punch/${date}`, { headers: authHeaders(token) })
      .then((res) => (res.status === 204 ? null : handle<AttendanceRecord>(res))),

  /** Every check-in/check-out session for a single day, e.g. to show a lunch-break gap. */
  punches: (date: string, token: string) =>
    fetch(`${BASE}/attendance/punches/${date}`, { headers: authHeaders(token) })
      .then(handle<Punch[]>),

  /** Avg. Work Hours Leaderboard for the manager's direct reports over a date range (ONEHR-106). */
  teamEffort: (from: string, to: string, token: string) =>
    fetch(`${BASE}/attendance/team-effort?from=${from}&to=${to}`, { headers: authHeaders(token) })
      .then(handle<TeamEffortEntry[]>),

  /** Late Arrivals, Least Hours Worked, and Frequent Breaks for direct reports (ONEHR-107). */
  teamNegligence: (from: string, to: string, token: string) =>
    fetch(`${BASE}/attendance/team-negligence?from=${from}&to=${to}`, { headers: authHeaders(token) })
      .then(handle<TeamNegligenceResponse>),
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

  // Same reviewer scoping as `pending`, but every status — backs the Pending Approvals
  // screen's All/Pending/Approved/Rejected status tabs (Manager/HR/Super Admin only).
  forApprover: (token: string) =>
    fetch(`${BASE}/attendance/regularization/for-approver`, { headers: authHeaders(token) }).then(r => handle<RegularizationRecord[]>(r)),

  approve: (id: string, token: string, comment?: string) =>
    fetch(`${BASE}/attendance/regularization/${id}/approve`, {
      method: 'PATCH', headers: authHeaders(token),
      body: JSON.stringify({ comment: comment || undefined }),
    }).then(r => handle<RegularizationRecord>(r)),

  reject: (id: string, comment: string, token: string) =>
    fetch(`${BASE}/attendance/regularization/${id}/reject`, {
      method: 'PATCH', headers: authHeaders(token), body: JSON.stringify({ comment }),
    }).then(r => handle<RegularizationRecord>(r)),

  // Each id is processed independently server-side — one failure doesn't affect the rest.
  bulkApprove: (ids: string[], token: string, comment?: string) =>
    fetch(`${BASE}/attendance/regularization/bulk-approve`, {
      method: 'POST', headers: authHeaders(token), body: JSON.stringify({ ids, comment: comment || undefined }),
    }).then(r => handle<BulkActionResult>(r)),

  bulkReject: (ids: string[], comment: string, token: string) =>
    fetch(`${BASE}/attendance/regularization/bulk-reject`, {
      method: 'POST', headers: authHeaders(token), body: JSON.stringify({ ids, comment }),
    }).then(r => handle<BulkActionResult>(r)),

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
