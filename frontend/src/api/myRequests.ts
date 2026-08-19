import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/my-requests`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error((body as any).message ?? `Request failed (${res.status})`);
  return body as T;
}

export type RequestType = 'LEAVE' | 'REGULARIZATION' | 'WEB_CLOCK_IN' | 'WFH' | 'PARTIAL_DAY' | 'OVERTIME';

export interface MyRequestItem {
  id: string;
  requestType: RequestType;
  employeeUserId: string;
  employeeName: string;
  createdAt: string;
  status: string;
  decisionReason?: string;
  decidedByName?: string;
  decidedAt?: string;

  // Leave
  leaveTypeName?: string;
  leaveStartDate?: string;
  leaveEndDate?: string;
  leaveTotalDays?: number;
  leaveHalfDay?: boolean;
  leaveReason?: string;

  // Regularization / WFH / Partial Day / Overtime (shared — see backend MyRequestItemDto)
  attendanceDate?: string;
  requestedCheckIn?: string;
  requestedCheckOut?: string;
  regularizationReason?: string;
  /** PARTIAL_DAY only. */
  partialDayHours?: number;
}

export const myRequestsApi = {
  list: (token: string) =>
    fetch(`${BASE}`, { headers: authHeaders(token) }).then(handle<MyRequestItem[]>),
};
