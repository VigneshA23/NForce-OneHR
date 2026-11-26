import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/overtime-management`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* empty / non-json body */ }
  if (!res.ok) throw new Error(body.message ?? `Request failed (${res.status})`);
  return body as T;
}

export type OtDayType = 'ANY' | 'WEEKDAY' | 'WEEKLY_OFF' | 'HOLIDAY';
export type OtStatus = 'CALCULATED' | 'APPROVED' | 'REJECTED';

export interface OtCalculation {
  id: string;
  employeeUserId: string;
  employeeCode: string | null;
  employeeName: string;
  departmentName: string | null;
  shiftName: string | null;
  workDate: string;
  scheduledMinutes: number;
  workedMinutes: number;
  otMinutes: number;
  dayType: OtDayType;
  multiplier: number;
  hourlyRate: number;
  otAmount: number;
  status: OtStatus;
  adjusted: boolean;
  hasPendingAdjustment: boolean;
  reviewComment: string | null;
  payrollRunId: string | null;
  calculatedAt: string;
}

export interface OtSkipped { employeeUserId: string; employeeName: string; workDate: string; reason: string }
export interface OtRunResult { created: number; updated: number; unchanged: number; skipped: OtSkipped[] }

export interface OtHistory {
  id: string; event: string; workedMinutes: number | null; otMinutes: number | null; multiplier: number | null;
  hourlyRate: number | null; otAmount: number | null; status: string | null; note: string | null;
  actorName: string | null; createdAt: string;
}

export interface OtSettings {
  minOtMinutes: number; roundingMinutes: number; workingDaysPerMonth: number; requireApprovedRequest: boolean;
  maxRequestMinutes: number; allowDuplicateRequests: boolean; adjustmentApprovalRequired: boolean;
}

export interface OtRateRule {
  id: string; name: string; dayType: OtDayType; departmentId: string | null; departmentName: string | null;
  shiftId: string | null; shiftName: string | null; multiplier: number; effectiveFrom: string;
  effectiveTo: string | null; active: boolean;
}
export interface OtRateRuleInput {
  name: string; dayType: OtDayType; departmentId?: string | null; shiftId?: string | null; multiplier: number;
  effectiveFrom: string; effectiveTo?: string | null; active?: boolean;
}

export interface OtAdjustment {
  id: string; calculationId: string; employeeName: string; workDate: string; originalMinutes: number;
  originalAmount: number; newMinutes: number; newAmount: number; reason: string;
  status: 'PENDING' | 'APPLIED' | 'REJECTED'; requestedByName: string | null; requestedAt: string;
  decidedByName: string | null; decidedAt: string | null; decisionComment: string | null;
}

export interface OtPayrollRow {
  employeeUserId: string; employeeCode: string | null; employeeName: string; workDate: string; hours: number;
  hourlyRate: number; multiplier: number; amount: number;
}
export interface OtPayrollEmployee {
  employeeUserId: string; employeeCode: string | null; employeeName: string; approvedAmount: number;
  payslipAmount: number; discrepancy: boolean; rows: OtPayrollRow[];
}
export interface OtPayrollSummary {
  runId: string; cycleStart: string; cycleEnd: string; totalTransferred: number; totalApprovedInCycle: number;
  employees: OtPayrollEmployee[];
}

const get = <T>(path: string, token: string) =>
  fetch(`${BASE}${path}`, { headers: authHeaders(token) }).then(r => handle<T>(r));
const send = <T>(method: string, path: string, token: string, body?: unknown) =>
  fetch(`${BASE}${path}`, { method, headers: authHeaders(token), body: body === undefined ? undefined : JSON.stringify(body) })
    .then(r => handle<T>(r));

export const overtimeManagementApi = {
  run: (token: string, from: string, to: string) => send<OtRunResult>('POST', '/calculations/run', token, { from, to }),
  list: (token: string, from: string, to: string, status?: string) =>
    get<OtCalculation[]>(`/calculations?from=${from}&to=${to}${status ? `&status=${status}` : ''}`, token),
  decide: (token: string, ids: string[], decision: 'APPROVE' | 'REJECT', comment?: string) =>
    send<{ updated: number }>('POST', '/calculations/decision', token, { ids, decision, comment }),
  history: (token: string, id: string) => get<OtHistory[]>(`/calculations/${id}/history`, token),
  adjust: (token: string, id: string, body: { otMinutes?: number | null; otAmount?: number | null; reason: string }) =>
    send<OtAdjustment>('POST', `/calculations/${id}/adjust`, token, body),
  adjustments: (token: string) => get<OtAdjustment[]>('/adjustments', token),
  decideAdjustment: (token: string, id: string, decision: 'APPROVE' | 'REJECT', comment?: string) =>
    send<OtAdjustment>('POST', `/adjustments/${id}/decision`, token, { decision, comment }),
  settings: (token: string) => get<OtSettings>('/settings', token),
  updateSettings: (token: string, s: OtSettings) => send<OtSettings>('PUT', '/settings', token, s),
  rules: (token: string) => get<OtRateRule[]>('/rate-rules', token),
  createRule: (token: string, r: OtRateRuleInput) => send<OtRateRule>('POST', '/rate-rules', token, r),
  updateRule: (token: string, id: string, r: OtRateRuleInput) => send<OtRateRule>('PUT', `/rate-rules/${id}`, token, r),
  deactivateRule: (token: string, id: string) => send<void>('DELETE', `/rate-rules/${id}`, token),
  payrollSummary: (token: string, runId: string) => get<OtPayrollSummary>(`/payroll/${runId}/summary`, token),
};
