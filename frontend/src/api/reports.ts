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

export type AttendanceRequestReportType = 'REGULARIZATION' | 'WEB_CLOCK_IN';

export interface AttendanceRequestReportRow {
  employeeUserId: string;
  employeeCode: string | null;
  fullName: string | null;
  date: string;
  checkIn: string | null;
  checkOut: string | null;
  reason: string | null;
  status: string;
}

export const reportsApi = {
  attendanceRequests: (type: AttendanceRequestReportType, from: string, to: string, token: string) =>
    fetch(`${BASE}/reports/attendance-requests?type=${type}&from=${from}&to=${to}`, { headers: authHeaders(token) })
      .then(r => handle<AttendanceRequestReportRow[]>(r)),

  /** Triggers a browser download of the CSV export — no JSON body to parse on success. */
  exportAttendanceRequests: async (type: AttendanceRequestReportType, from: string, to: string, token: string) => {
    const res = await fetch(`${BASE}/reports/attendance-requests/export?type=${type}&from=${from}&to=${to}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok) throw new Error(`Export failed (${res.status})`);
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${type.toLowerCase()}-report-${from}-to-${to}.csv`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  },
};
