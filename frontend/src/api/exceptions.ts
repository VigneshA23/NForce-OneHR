const BASE = '/api';

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error((body as any).message ?? `Request failed (${res.status})`);
  return body as T;
}

function dateRangeQuery(from?: string, to?: string): string {
  const params = new URLSearchParams();
  if (from) params.set('from', from);
  if (to) params.set('to', to);
  const qs = params.toString();
  return qs ? `?${qs}` : '';
}

export interface ExceptionRecord {
  id: string;
  employeeUserId: string;
  employeeCode: string | null;
  employeeFullName: string | null;
  exceptionDate: string;
  exceptionType: string;
  expectedTime: string | null;
  actualTime: string | null;
  minutesLate: number | null;
  status: string;
  detectedAt: string;
}

// TEMPORARY payload/record shapes — delete alongside the backend placeholder
// check-in scaffolding once FR-004 (Attendance Management) ships.
export interface PlaceholderCheckinPayload {
  employeeUserId: string;
  workDate: string;
  checkinTime: string;
  shiftStartTime?: string;
  lateThresholdMinutes?: number;
}

export interface PlaceholderCheckinRecord {
  id: string;
  employeeUserId: string;
  employeeFullName: string | null;
  workDate: string;
  shiftStartTime: string;
  checkinTime: string;
  lateThresholdMinutes: number;
  createdAt: string;
}

export const exceptionsApi = {
  list: (token: string, from?: string, to?: string) =>
    fetch(`${BASE}/exceptions${dateRangeQuery(from, to)}`, { headers: authHeaders(token) }).then(handle<ExceptionRecord[]>),

  seedPlaceholderCheckin: (payload: PlaceholderCheckinPayload, token: string) =>
    fetch(`${BASE}/exceptions/placeholder-checkins`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<PlaceholderCheckinRecord>),

  listPlaceholderCheckins: (token: string, from?: string, to?: string) =>
    fetch(`${BASE}/exceptions/placeholder-checkins${dateRangeQuery(from, to)}`, { headers: authHeaders(token) }).then(handle<PlaceholderCheckinRecord[]>),
};
