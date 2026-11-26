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

function withPolicyId(path: string, policyId?: string) {
  return policyId ? `${path}?policyId=${encodeURIComponent(policyId)}` : path;
}

// ── Types (mirrors backend dto/penalization/*) ─────────────────────────────

/** PN-03: early-warning notification settings, part of Basic Information. Off by default. */
export interface EarlyWarningConfig {
  enabled: boolean;
  lateArrivalEnabled: boolean;
  missingLogsEnabled: boolean;
  shortageEnabled: boolean;
  /** Warn when this many (or fewer) exempt late marks / missing-log days remain. 1 = limit minus 1. */
  exemptRemaining: number;
  /** Warn when the period's work-hours % is within this many points above a shortage tier. */
  shortageMarginPercent: number;
}

export const DEFAULT_EARLY_WARNING: EarlyWarningConfig = {
  enabled: false, lateArrivalEnabled: true, missingLogsEnabled: true, shortageEnabled: true, exemptRemaining: 1, shortageMarginPercent: 10,
};

export interface BasicInfoConfig {
  earlyWarning?: EarlyWarningConfig;
  deductionMethod: 'LOSS_OF_PAY' | 'PAID_LEAVE';
  /** LeaveType.code values in cascade priority order — only meaningful when deductionMethod is PAID_LEAVE. */
  leavePriorityOrder: string[];
  bufferPeriodDays: number | null;
  noticePeriodForcesLopEnabled: boolean;
  /**
   * Request-only: an admin-chosen future effective date (YYYY-MM-DD) for the version being saved.
   * Null (the default) defers to the backend's original behavior — effective the 1st of next
   * calendar month. Never populated on a response's own basicInfo — the version's actual resolved
   * effective date is PenalizationPolicy.effectiveFrom at the top level.
   */
  requestedEffectiveFrom?: string | null;
}

export interface NoAttendanceConfig {
  enabled: boolean;
  deductionDays: number | null;
  noShowEnabled: boolean;
  noShowThresholdHours: number | null;
  adjoiningHolidayEnabled: boolean;
  adjoiningHolidayCondition: 'SANDWICHED' | 'BEFORE' | 'AFTER' | 'ANY' | null;
  adjoiningHolidayCalendarDayThreshold: number | null;
  adjoiningHolidayIgnoreHalfDayLeave: boolean;
  adjoiningWeekoffEnabled: boolean;
  adjoiningWeekoffCondition: 'SANDWICHED' | 'BEFORE' | 'AFTER' | 'ANY' | null;
  adjoiningWeekoffCalendarDayThreshold: number | null;
  adjoiningWeekoffIgnoreHalfDayLeave: boolean;
}

export interface LateHoursTier {
  thresholdHours: number;
  deductionDays: number;
}

export interface LateArrivalConfig {
  enabled: boolean;
  basis: 'NUMBER_OF_INCIDENTS' | 'TOTAL_HOURS';
  gracePeriodMinutes: number | null;
  exemptCount: number | null;
  exemptPeriod: 'WEEK' | 'MONTH';
  deductionDays: number | null;
  deductionPerShifts: number | null;
  ignoreWhenEffectiveHoursMetEnabled: boolean;
  allowedHours: number | null;
  lateHoursTiers: LateHoursTier[];
  combinedRuleBehavior: 'TOTAL_HOURS_ONLY' | 'BOTH';
  penaliseWhenCausedByMissingLogEnabled: boolean;
}

export interface WorkHoursTier {
  thresholdPercent: number;
  deductionDays: number;
}

export interface WorkHoursShortageConfig {
  enabled: boolean;
  deductionBasis: 'EFFECTIVE_HOURS' | 'GROSS_HOURS';
  deductionPeriod: 'DAY' | 'WEEK' | 'MONTH';
  tiers: WorkHoursTier[];
  applyPenaltyForShortageEnabled: boolean;
  applyPenaltyForLateArrivalEnabled: boolean;
  /** "Exclude hours worked outside the assigned shift timing" from the shortage calculation. */
  excludeHoursOutsideShiftEnabled: boolean;
  /** "Penalize shortage caused by missing logs" — a day with no check-out is otherwise never evaluated for shortage. */
  penalizeShortageCausedByMissingLogsEnabled: boolean;
}

export interface MissingLogsConfig {
  enabled: boolean;
  exemptDays: number | null;
  exemptPeriod: 'WEEK' | 'MONTH';
  deductionMode: 'PER_SHIFT' | 'IRRESPECTIVE';
  deductionDays: number | null;
  deductionPerShifts: number | null;
  ignoreRuleEnabled: boolean;
  ignoreRuleThresholdPercent: number | null;
}

/** PN-05: leaving before the shift end. Same shape as the Late Arrival incident basis. */
export interface EarlyGoingConfig {
  enabled: boolean;
  gracePeriodMinutes: number | null;
  exemptCount: number | null;
  exemptPeriod: 'WEEK' | 'MONTH';
  deductionDays: number | null;
  deductionPerShifts: number | null;
}

/** PN-05: total break time in a day above a limit. */
export interface ExcessBreakConfig {
  enabled: boolean;
  maxBreakMinutes: number | null;
  exemptCount: number | null;
  exemptPeriod: 'WEEK' | 'MONTH';
  deductionDays: number | null;
}

export const DEFAULT_EARLY_GOING: EarlyGoingConfig = {
  enabled: false, gracePeriodMinutes: null, exemptCount: null, exemptPeriod: 'MONTH', deductionDays: null, deductionPerShifts: null,
};
export const DEFAULT_EXCESS_BREAK: ExcessBreakConfig = {
  enabled: false, maxBreakMinutes: null, exemptCount: null, exemptPeriod: 'MONTH', deductionDays: null,
};

export interface PenalizationPolicy {
  id: string;
  policyId: string;
  version: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  basicInfo: BasicInfoConfig;
  noAttendance: NoAttendanceConfig;
  lateArrival: LateArrivalConfig;
  workHoursShortage: WorkHoursShortageConfig;
  missingLogs: MissingLogsConfig;
  /** PN-05. Absent on a response from a server that predates the rules: treat as off. */
  earlyGoing?: EarlyGoingConfig;
  excessBreak?: ExcessBreakConfig;
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
  basicInfo: BasicInfoConfig;
  noAttendance: NoAttendanceConfig;
  lateArrival: LateArrivalConfig;
  workHoursShortage: WorkHoursShortageConfig;
  missingLogs: MissingLogsConfig;
  earlyGoing: EarlyGoingConfig;
  excessBreak: ExcessBreakConfig;
}

// ── API ──────────────────────────────────────────────────────────────────
// Every function takes an optional policyId (Section 5's Policy List) — omit it to operate on
// the org's original single policy, unchanged from before Policy List existed.

/** Null when no policy has ever been saved — render "Not configured". */
export async function getCurrentPolicy(token: string, policyId?: string): Promise<PenalizationPolicy | null> {
  const res = await fetch(withPolicyId(`${BASE}/current`, policyId), { headers: authHeaders(token) });
  if (res.status === 404) return null;
  return handle(res);
}

/**
 * Section 25: the policy actually governing the calling employee's own attendance right now —
 * any authenticated employee may call this (unlike every other function here, which requires
 * HR_ADMIN/SUPER_ADMIN). Null when no policy has ever been saved for their resolved policy.
 */
export async function getMyCurrentPolicy(token: string): Promise<PenalizationPolicy | null> {
  const res = await fetch(`${BASE}/my-current`, { headers: authHeaders(token) });
  if (res.status === 404) return null;
  return handle(res);
}

export async function getPolicyVersions(token: string, policyId?: string): Promise<PenalizationPolicyVersionSummary[]> {
  return handle(await fetch(withPolicyId(`${BASE}/versions`, policyId), { headers: authHeaders(token) }));
}

export async function getPolicyVersion(token: string, id: string): Promise<PenalizationPolicy> {
  return handle(await fetch(`${BASE}/versions/${id}`, { headers: authHeaders(token) }));
}

export async function savePolicy(token: string, body: PenalizationPolicyRequest, policyId?: string): Promise<PenalizationPolicy> {
  return handle(await fetch(withPolicyId(BASE, policyId), { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(body) }));
}
