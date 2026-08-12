import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/penalization-policy`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error(body.message ?? `Request failed (${res.status})`);
  return body as T;
}

// ── Types (mirrors backend dto/penalization/*) ─────────────────────────────

export interface NoAttendanceConfig {
  enabled: boolean;
  deductionDays: number | null;
  noShowEnabled: boolean;
  noShowThresholdHours: number | null;
}

export interface LateArrivalConfig {
  enabled: boolean;
  basis: 'NUMBER_OF_INCIDENTS';
  gracePeriodMinutes: number | null;
  exemptCount: number | null;
  exemptPeriod: 'MONTH';
  deductionDays: number | null;
  deductionPerShifts: number | null;
  ignoreWhenEffectiveHoursMetEnabled: boolean;
}

export interface WorkHoursTier {
  thresholdPercent: number;
  deductionDays: number;
}

export interface WorkHoursShortageConfig {
  enabled: boolean;
  deductionBasis: 'EFFECTIVE_HOURS';
  deductionPeriod: 'DAY';
  tiers: WorkHoursTier[];
  applyPenaltyForShortageEnabled: boolean;
  applyPenaltyForLateArrivalEnabled: boolean;
}

export interface MissingLogsConfig {
  enabled: boolean;
  exemptDays: number | null;
  exemptPeriod: 'MONTH';
  deductionMode: 'PER_SHIFT' | 'IRRESPECTIVE';
  deductionDays: number | null;
  deductionPerShifts: number | null;
  ignoreRuleEnabled: boolean;
  ignoreRuleThresholdPercent: number | null;
}

export interface PenalizationPolicy {
  id: string;
  policyId: string;
  version: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  noAttendance: NoAttendanceConfig;
  lateArrival: LateArrivalConfig;
  workHoursShortage: WorkHoursShortageConfig;
  missingLogs: MissingLogsConfig;
  createdBy: string;
  createdAt: string;
}

export interface PenalizationPolicyVersionSummary {
  id: string;
  version: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  createdAt: string;
}

export interface PenalizationPolicyRequest {
  noAttendance: NoAttendanceConfig;
  lateArrival: LateArrivalConfig;
  workHoursShortage: WorkHoursShortageConfig;
  missingLogs: MissingLogsConfig;
}

// ── API ──────────────────────────────────────────────────────────────────

/** Null when no policy has ever been saved — render "Not configured". */
export async function getCurrentPolicy(token: string): Promise<PenalizationPolicy | null> {
  const res = await fetch(`${BASE}/current`, { headers: authHeaders(token) });
  if (res.status === 404) return null;
  return handle(res);
}

export async function getPolicyVersions(token: string): Promise<PenalizationPolicyVersionSummary[]> {
  return handle(await fetch(`${BASE}/versions`, { headers: authHeaders(token) }));
}

export async function getPolicyVersion(token: string, id: string): Promise<PenalizationPolicy> {
  return handle(await fetch(`${BASE}/versions/${id}`, { headers: authHeaders(token) }));
}

export async function savePolicy(token: string, body: PenalizationPolicyRequest): Promise<PenalizationPolicy> {
  return handle(await fetch(BASE, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(body) }));
}
