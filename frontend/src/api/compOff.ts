import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/comp-off`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* empty / non-json body */ }
  if (!res.ok) throw new Error(body.message ?? `Request failed (${res.status})`);
  return body as T;
}

const get = <T>(path: string, token: string) => fetch(`${BASE}${path}`, { headers: authHeaders(token) }).then(handle<T>);
const send = <T>(method: string, path: string, token: string, body?: unknown) =>
  fetch(`${BASE}${path}`, { method, headers: authHeaders(token), body: body === undefined ? undefined : JSON.stringify(body) }).then(handle<T>);

export type CompOffChoice = 'PENDING' | 'OT' | 'COMP_OFF';
export type CreditStatus = 'PENDING_APPROVAL' | 'AVAILABLE' | 'REJECTED' | 'EXPIRED';

export interface CompOffPolicy { overtimeEnabled: boolean; compOffEnabled: boolean; allowPartialDay: boolean }

export interface CompOffSettings {
  overtimeEnabled: boolean; compOffEnabled: boolean; eligibleWeeklyOff: boolean; eligibleHoliday: boolean;
  includeWeekdayExtra: boolean; minMinutesHalfDay: number; minMinutesFullDay: number; validityDays: number;
  requireCreditApproval: boolean; allowPartialDay: boolean; expiryNoticeDays: number; updatedAt: string;
}
export type CompOffRules = Omit<CompOffSettings, 'overtimeEnabled' | 'compOffEnabled' | 'updatedAt'>;

export interface CompOffBalance { earned: number; available: number; utilized: number; pending: number; expired: number; adjusted: number }

export interface CompOffClaim {
  id: string; employeeUserId: string; employeeCode: string | null; employeeName: string; workDate: string;
  dayType: 'WEEKDAY' | 'WEEKLY_OFF' | 'HOLIDAY'; workedMinutes: number; eligibleMinutes: number; creditDays: number;
  choice: CompOffChoice; creditStatus: CreditStatus | null; expiresOn: string | null;
}

export interface CompOffCredit {
  id: string; employeeUserId: string; employeeCode: string | null; employeeName: string; departmentName: string | null;
  source: 'EARNED' | 'MANUAL'; workDate: string | null; creditDays: number; usedDays: number; deductedDays: number;
  expiredDays: number; remainingDays: number; status: CreditStatus; earnedOn: string; expiresOn: string;
  reason: string | null; reviewComment: string | null;
}

export interface CompOffEvent { id: string; event: string; days: number | null; note: string | null; actorName: string | null; createdAt: string }

export interface MyCompOff { policy: CompOffPolicy; balance: CompOffBalance; claims: CompOffClaim[]; credits: CompOffCredit[]; history: CompOffEvent[] }

export interface CompOffSummaryRow {
  employeeUserId: string; employeeCode: string | null; employeeName: string; departmentName: string; managerName: string | null;
  earned: number; available: number; utilized: number; pending: number; expired: number; adjusted: number;
  departmentId: string | null; managerUserId: string | null;
}
export interface CompOffDepartmentRow {
  departmentName: string; employees: number; earned: number; available: number; utilized: number; pending: number; expired: number; adjusted: number;
}
export interface CompOffReport { employees: CompOffSummaryRow[]; departments: CompOffDepartmentRow[] }

export interface CompOffDetectResult {
  created: number; autoCredited: number; unchanged: number;
  skipped: { employeeUserId: string; employeeName: string; workDate: string; reason: string }[];
}

export interface ReportFilter { employeeId?: string; departmentId?: string; managerId?: string; from?: string; to?: string }

const qs = (f: ReportFilter) => {
  const p = new URLSearchParams();
  Object.entries(f).forEach(([k, v]) => { if (v) p.set(k, v); });
  const s = p.toString();
  return s ? `?${s}` : '';
};

export const compOffApi = {
  policy: (token: string) => get<CompOffPolicy>('/policy', token),
  mine: (token: string) => get<MyCompOff>('/me', token),
  choose: (token: string, claimId: string, choice: 'OT' | 'COMP_OFF') =>
    send<CompOffClaim>('POST', `/claims/${claimId}/choose`, token, { choice }),

  settings: (token: string) => get<CompOffSettings>('/settings', token),
  updatePolicy: (token: string, overtimeEnabled: boolean, compOffEnabled: boolean) =>
    send<CompOffSettings>('PUT', '/policy', token, { overtimeEnabled, compOffEnabled }),
  updateRules: (token: string, rules: CompOffRules) => send<CompOffSettings>('PUT', '/settings', token, rules),

  detect: (token: string, from: string, to: string) => send<CompOffDetectResult>('POST', '/detect', token, { from, to }),
  claims: (token: string, from: string, to: string) => get<CompOffClaim[]>(`/claims?from=${from}&to=${to}`, token),
  approvals: (token: string) => get<CompOffCredit[]>('/approvals', token),
  decide: (token: string, id: string, decision: 'APPROVE' | 'REJECT', comment?: string) =>
    send<CompOffCredit>('POST', `/credits/${id}/decision`, token, { decision, comment }),
  adjust: (token: string, employeeUserId: string, days: number, reason: string) =>
    send<CompOffCredit>('POST', '/adjustments', token, { employeeUserId, days, reason }),
  history: (token: string, employeeId: string) => get<CompOffEvent[]>(`/employees/${employeeId}/history`, token),

  report: (token: string, f: ReportFilter) => get<CompOffReport>(`/report${qs(f)}`, token),
  credits: (token: string, f: ReportFilter) => get<CompOffCredit[]>(`/credits${qs(f)}`, token),
  exportXlsx: async (token: string, f: ReportFilter) => {
    const res = await fetch(`${BASE}/report/export${qs(f)}`, { headers: { Authorization: `Bearer ${token}` } });
    if (!res.ok) throw new Error(`Export failed (${res.status})`);
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement('a');
    a.href = url; a.download = 'comp-off-report.xlsx'; a.click();
    URL.revokeObjectURL(url);
  },
};
