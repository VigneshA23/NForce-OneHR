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
/** Only meaningful for PARTIAL_DAY (Keka reference: one radio choice within the same request). */
export type PartialDayMode = 'LATE_ARRIVE' | 'INTERVENING_TIMEOFF' | 'LEAVING_EARLY';
/** Only meaningful for WFH — which portion of that day counts toward the day quota. */
export type WfhDayMode = 'FULL_DAY' | 'FIRST_HALF' | 'SECOND_HALF';

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
  /** PARTIAL_DAY: PartialDayMode. WFH: WfhDayMode. Null for neither. */
  partialDayMode: PartialDayMode | WfhDayMode | null;
  /** Only meaningful for WFH (1 = Full Day, 0.5 = First/Second Half) — null for Partial Day. */
  wfhDayFraction: number | null;
  reason: string;
  status: AttendanceRequestStatus;
  assignedApproverId: string | null;
  assignedApproverName: string | null;
  /** A colleague notified about this request — informational only, not an approver. */
  notifyUserId: string | null;
  notifyUserName: string | null;
  reviewedByName: string | null;
  reviewedAt: string | null;
  reviewComment: string | null;
  createdAt: string;
}

export interface SubmitAttendanceRequestPayload {
  requestType: AttendanceRequestType;
  requestDate: string;
  partialDayHours?: number;
  partialDayMode?: PartialDayMode | WfhDayMode;
  reason: string;
  managerUserId?: string;
  notifyUserId?: string;
}

export interface PartialDayBalance {
  usedHours: number;
  limitHours: number;
  remainingHours: number;
}

export interface WfhBalance {
  usedDays: number;
  limitDays: number;
  remainingDays: number;
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

  /** "View Available Balance" — hours already committed in `date`'s month vs. the monthly cap. */
  partialDayBalance: (date: string, token: string) =>
    fetch(`${BASE}/partial-day-balance?date=${date}`, { headers: authHeaders(token) })
      .then(r => handle<PartialDayBalance>(r)),

  /** WFH's "Remaining balance" line — days already committed in `date`'s month vs. the current
   * monthly cap (Super Admin-configurable, see wfhPartialLeavePolicyApi). */
  wfhBalance: (date: string, token: string) =>
    fetch(`${BASE}/wfh-balance?date=${date}`, { headers: authHeaders(token) })
      .then(r => handle<WfhBalance>(r)),
};
