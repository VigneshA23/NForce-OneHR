import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/payslips`;

/** Employee self-service — PayslipController resolves "me" from the JWT principal itself, never
 * an employeeId the client supplies, so there's no permission gate to check here (any
 * authenticated user can see their own payslips, same as /api/payslips/me server-side). */
export const payslipsApi = {
  /** `yyyy-MM` periods, newest first. */
  listMyPeriods: (token: string) =>
    fetch(`${BASE}/me`, { headers: { Authorization: `Bearer ${token}` } })
      .then(res => { if (!res.ok) throw new Error(`Failed to load payslips (${res.status})`); return res.json() as Promise<string[]>; }),

  /** Downloads that period's payslip as a PDF — same fetch-blob-anchor pattern as
   * reports.ts#exportAttendanceRequests, the one existing binary-download convention in this app. */
  downloadMyPayslip: async (period: string, token: string) => {
    const res = await fetch(`${BASE}/me/${period}`, { headers: { Authorization: `Bearer ${token}` } });
    if (!res.ok) throw new Error(`Download failed (${res.status})`);
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `payslip-${period}.pdf`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  },
};
