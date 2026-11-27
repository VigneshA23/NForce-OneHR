/**
 * LV-01 plan editor logic, kept free of React so it can be unit tested: a plan version's type rules as
 * editable form rows (one per catalogue type, included or not), validation, and the request payload.
 */
import type {
  CarryForwardPriority, CreditFrequency, EligibilityConfig, EntitlementMode, LeaveRuleSummary, LeaveType, PlanRuleLimits,
  LeavePreview, PlanTypeRule, PlanTypeRulePayload, SandwichAppliesTo,
} from '../../api/leave';

export const EMPTY_RULE_ELIGIBILITY: EligibilityConfig = {
  genders: [], departmentIds: [], locationIds: [], employmentTypes: [], includeEmployeeIds: [], excludeEmployeeIds: [],
  maritalStatuses: [], grades: [], minTenureMonths: null, allowedDuringProbation: true,
};

export interface RuleForm {
  leaveTypeId: string;
  included: boolean;
  days: string;
  creditFrequency: CreditFrequency;
  carryForwardEnabled: boolean;
  carryForwardMaxDays: string;
  carryForwardExpiryMonths: string;
  carryForwardPriority: CarryForwardPriority;
  eligibility: EligibilityConfig;
  limits: LimitsForm;
}

/** LV-02 request rules as typed in the editor ('' = no limit). */
export interface LimitsForm {
  entitlementMode: EntitlementMode;
  minDaysPerRequest: string;
  maxDaysPerRequest: string;
  maxRequestsPerMonth: string;
  maxRequestsPerYear: string;
  maxConsecutiveDays: string;
  noticeDays: string;
  noticeAppliesOverDays: string;
  allowHalfDay: boolean;
  allowHourly: boolean;
  minHours: string;
  attachmentRequiredOverDays: string;
  attachmentAllowedTypes: string;
  attachmentMaxMb: string;
  notNextToTypeIds: string[];
  sandwichEnabled: boolean;
  sandwichAppliesTo: SandwichAppliesTo;
  sandwichMinDays: string;
  sandwichAcrossRequests: boolean;
}

export const EMPTY_LIMITS: LimitsForm = {
  entitlementMode: 'QUOTA', minDaysPerRequest: '', maxDaysPerRequest: '', maxRequestsPerMonth: '', maxRequestsPerYear: '',
  maxConsecutiveDays: '', noticeDays: '', noticeAppliesOverDays: '', allowHalfDay: true, allowHourly: false, minHours: '',
  attachmentRequiredOverDays: '', attachmentAllowedTypes: '', attachmentMaxMb: '', notNextToTypeIds: [],
  sandwichEnabled: false, sandwichAppliesTo: 'BOTH', sandwichMinDays: '', sandwichAcrossRequests: false,
};

function limitsForm(l: PlanRuleLimits | null | undefined): LimitsForm {
  if (!l) return EMPTY_LIMITS;
  return {
    entitlementMode: l.entitlementMode ?? 'QUOTA',
    minDaysPerRequest: str(l.minDaysPerRequest), maxDaysPerRequest: str(l.maxDaysPerRequest),
    maxRequestsPerMonth: str(l.maxRequestsPerMonth), maxRequestsPerYear: str(l.maxRequestsPerYear),
    maxConsecutiveDays: str(l.maxConsecutiveDays), noticeDays: str(l.noticeDays), noticeAppliesOverDays: str(l.noticeAppliesOverDays),
    allowHalfDay: l.allowHalfDay, allowHourly: l.allowHourly, minHours: str(l.minHours),
    attachmentRequiredOverDays: str(l.attachmentRequiredOverDays), attachmentAllowedTypes: l.attachmentAllowedTypes ?? '',
    attachmentMaxMb: str(l.attachmentMaxMb), notNextToTypeIds: l.notNextToTypeIds ?? [],
    sandwichEnabled: l.sandwichEnabled ?? false, sandwichAppliesTo: l.sandwichAppliesTo ?? 'BOTH',
    sandwichMinDays: str(l.sandwichMinDays), sandwichAcrossRequests: l.sandwichAcrossRequests ?? false,
  };
}

const str = (n: number | null | undefined) => (n == null ? '' : String(Number(n)));

/** One row per catalogue type: the plan's rule where it has one, otherwise a not-included row with sensible prefills. */
export function formsFor(types: LeaveType[], rules: PlanTypeRule[]): RuleForm[] {
  const byType = new Map(rules.map(r => [r.leaveTypeId, r]));
  return types
    .filter(t => t.active || byType.has(t.id))
    .map(t => {
      const r = byType.get(t.id);
      if (r) {
        return {
          leaveTypeId: t.id, included: true, days: str(r.days), creditFrequency: r.creditFrequency ?? 'YEARLY',
          carryForwardEnabled: r.carryForwardEnabled, carryForwardMaxDays: str(r.carryForwardMaxDays),
          carryForwardExpiryMonths: str(r.carryForwardExpiryMonths), carryForwardPriority: r.carryForwardPriority,
          eligibility: r.eligibility ?? EMPTY_RULE_ELIGIBILITY, limits: limitsForm(r.limits),
        };
      }
      return {
        leaveTypeId: t.id, included: false, days: t.entitlementBasis === 'PER_OCCURRENCE' ? '182' : '15', creditFrequency: 'MONTHLY',
        carryForwardEnabled: false, carryForwardMaxDays: '', carryForwardExpiryMonths: '', carryForwardPriority: 'CARRY_FORWARD_FIRST',
        eligibility: EMPTY_RULE_ELIGIBILITY, limits: EMPTY_LIMITS,
      };
    });
}

/** The payload for the included rows, or the first problem found (named after the leave type). */
export function toPayload(forms: RuleForm[], types: LeaveType[]): { rules: PlanTypeRulePayload[]; error?: string } {
  const typeById = new Map(types.map(t => [t.id, t]));
  const rules: PlanTypeRulePayload[] = [];
  for (const f of forms) {
    if (!f.included) continue;
    const t = typeById.get(f.leaveTypeId);
    if (!t) continue;
    const paid = t.classification === 'PAID';
    const annual = paid && t.entitlementBasis !== 'PER_OCCURRENCE';
    const daysN = Number(f.days);
    const mode = modeFor(t, f.limits);
    if (paid && mode === 'QUOTA' && (f.days.trim() === '' || isNaN(daysN) || daysN < 0 || daysN > 999)) {
      return { rules: [], error: `${t.name}: days must be between 0 and 999` };
    }
    const cf = annual && f.carryForwardEnabled;
    const maxN = f.carryForwardMaxDays.trim() === '' ? null : Number(f.carryForwardMaxDays);
    const expN = f.carryForwardExpiryMonths.trim() === '' ? null : Number(f.carryForwardExpiryMonths);
    if (cf && maxN != null && (isNaN(maxN) || maxN < 0 || maxN > 999)) {
      return { rules: [], error: `${t.name}: the carry-forward maximum must be between 0 and 999 days` };
    }
    if (cf && expN != null && (!Number.isInteger(expN) || expN < 1 || expN > 24)) {
      return { rules: [], error: `${t.name}: carried-forward days must expire after 1–24 months (or never)` };
    }
    const limits = limitsPayload(t, f.limits, mode);
    if (typeof limits === 'string') return { rules: [], error: limits };
    if (cf && mode === 'UNLIMITED') return { rules: [], error: `${t.name}: unlimited leave has no balance to carry forward` };
    rules.push({
      leaveTypeId: t.id,
      days: paid ? (mode === 'QUOTA' ? daysN : 0) : null,
      creditFrequency: annual ? f.creditFrequency : null,
      carryForwardEnabled: cf,
      carryForwardMaxDays: cf ? maxN : null,
      carryForwardExpiryMonths: cf ? expN : null,
      carryForwardPriority: f.carryForwardPriority,
      eligibility: f.eligibility,
      limits,
    });
  }
  return { rules };
}

/** A number field: null when blank, within [min, max] (whole numbers when `int`), or an error message. */
function num(v: string, label: string, min: number, max: number, int = false): number | null | string {
  if (v.trim() === '') return null;
  const n = Number(v);
  if (isNaN(n) || n < min || n > max || (int && !Number.isInteger(n))) {
    return `${label} must be ${int ? 'a whole number ' : ''}between ${min} and ${max}`;
  }
  return n;
}

type NumericLimit = 'minDaysPerRequest' | 'maxDaysPerRequest' | 'maxRequestsPerMonth' | 'maxRequestsPerYear' | 'maxConsecutiveDays'
  | 'noticeDays' | 'noticeAppliesOverDays' | 'minHours' | 'attachmentRequiredOverDays' | 'attachmentMaxMb' | 'sandwichMinDays';

/** The limits payload for one type, or the first problem. Mirrors the server's checks. */
export function limitsPayload(t: LeaveType, f: LimitsForm, mode: EntitlementMode): PlanRuleLimits | string {
  const fields: [NumericLimit, string, number, number, boolean][] = [
    ['minDaysPerRequest', 'minimum days per request', 0.5, 366, false],
    ['maxDaysPerRequest', 'maximum days per request', 0.5, 366, false],
    ['maxRequestsPerMonth', 'requests a month', 1, 31, true],
    ['maxRequestsPerYear', 'requests a year', 1, 366, true],
    ['maxConsecutiveDays', 'days in a row', 0.5, 366, false],
    ['noticeDays', 'notice days', 1, 365, true],
    ['noticeAppliesOverDays', 'notice applies over', 0, 366, false],
    ['minHours', 'minimum hours', 0.5, 23.5, false],
    ['attachmentRequiredOverDays', 'attachment required over', 0, 366, false],
    ['attachmentMaxMb', 'maximum file size', 1, 10, true],
    ['sandwichMinDays', 'sandwich minimum days', 0.5, 366, false],
  ];
  const out = {} as Record<NumericLimit, number | null>;
  for (const [key, label, min, max, int] of fields) {
    const n = num(f[key], label, min, max, int);
    if (typeof n === 'string') return `${t.name}: ${n}`;
    out[key] = n;
  }
  if (out.minDaysPerRequest != null && out.maxDaysPerRequest != null && out.minDaysPerRequest > out.maxDaysPerRequest) {
    return `${t.name}: the minimum days per request can't be more than the maximum`;
  }
  if (out.maxDaysPerRequest != null && out.maxConsecutiveDays != null && out.maxConsecutiveDays < out.maxDaysPerRequest) {
    return `${t.name}: the maximum days in a row can't be less than the maximum days per request`;
  }
  if (out.maxRequestsPerMonth != null && out.maxRequestsPerYear != null && out.maxRequestsPerMonth > out.maxRequestsPerYear) {
    return `${t.name}: the requests allowed a month can't be more than a year`;
  }
  if (out.noticeAppliesOverDays != null && out.noticeDays == null) {
    return `${t.name}: set the notice days for "notice applies over" to mean anything`;
  }
  const exts = f.attachmentAllowedTypes.split(',').map(e => e.trim().toLowerCase().replace(/^\./, '')).filter(Boolean);
  const bad = exts.find(e => !/^[a-z0-9]{1,10}$/.test(e));
  if (bad) return `${t.name}: "${bad}" isn't a file extension (e.g. pdf, jpg)`;
  return {
    entitlementMode: mode,
    minDaysPerRequest: out.minDaysPerRequest, maxDaysPerRequest: out.maxDaysPerRequest,
    maxRequestsPerMonth: out.maxRequestsPerMonth, maxRequestsPerYear: out.maxRequestsPerYear,
    maxConsecutiveDays: out.maxConsecutiveDays, noticeDays: out.noticeDays,
    noticeAppliesOverDays: out.noticeDays == null ? null : out.noticeAppliesOverDays,
    allowHalfDay: f.allowHalfDay, allowHourly: f.allowHourly, minHours: f.allowHourly ? out.minHours : null,
    attachmentRequiredOverDays: out.attachmentRequiredOverDays,
    attachmentAllowedTypes: exts.length ? [...new Set(exts)].join(',') : null,
    attachmentMaxMb: out.attachmentMaxMb,
    notNextToTypeIds: f.notNextToTypeIds.filter(id => id !== t.id),
    // Per-occurrence leave (Maternity) counts calendar days, so the sandwich rule never applies to it.
    ...(f.sandwichEnabled && t.entitlementBasis !== 'PER_OCCURRENCE'
      ? { sandwichEnabled: true, sandwichAppliesTo: f.sandwichAppliesTo, sandwichMinDays: out.sandwichMinDays, sandwichAcrossRequests: f.sandwichAcrossRequests }
      : { sandwichEnabled: false, sandwichAppliesTo: 'BOTH' as const, sandwichMinDays: null, sandwichAcrossRequests: false }),
  };
}

const SANDWICH_WHAT: Record<SandwichAppliesTo, string> = {
  BOTH: 'Weekly offs and holidays', WEEK_OFFS: 'Weekly offs', HOLIDAYS: 'Holidays',
};

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;
const dayText = (n: number) => plural(n, 'day');

/**
 * LV-02: a type's rules as short sentences — the plan editor's collapsed row and the apply form's rules panel
 * both use this. Only rules that restrict something are listed.
 */
export function ruleLines(r: LeaveRuleSummary): string[] {
  const out: string[] = [];
  if (r.entitlementMode === 'UNLIMITED') out.push('Unlimited: nothing is deducted from a balance');
  if (r.entitlementMode === 'EARNED_ONLY') out.push('Only days credited to you (e.g. earned) can be used');
  if (r.minDaysPerRequest != null && r.maxDaysPerRequest != null) out.push(`${r.minDaysPerRequest}–${dayText(r.maxDaysPerRequest)} per request`);
  else if (r.minDaysPerRequest != null) out.push(`At least ${dayText(r.minDaysPerRequest)} per request`);
  else if (r.maxDaysPerRequest != null) out.push(`At most ${dayText(r.maxDaysPerRequest)} per request`);
  if (r.maxRequestsPerMonth != null) out.push(`At most ${plural(r.maxRequestsPerMonth, 'request')} a month`);
  if (r.maxRequestsPerYear != null) out.push(`At most ${plural(r.maxRequestsPerYear, 'request')} a year`);
  if (r.maxConsecutiveDays != null) out.push(`At most ${dayText(r.maxConsecutiveDays)} in a row`);
  if (r.noticeDays != null) {
    out.push(`Apply ${dayText(r.noticeDays)} ahead${r.noticeAppliesOverDays != null ? ` for more than ${dayText(r.noticeAppliesOverDays)}` : ''}`);
  }
  if (!r.allowHalfDay) out.push('No half days');
  if (r.allowHourly) out.push(`Can be taken by the hour${r.minHours != null ? ` (at least ${plural(r.minHours, 'hour')})` : ''}`);
  if (r.attachmentRequiredOverDays != null) {
    const what = [r.attachmentAllowedTypes.map(e => e.toUpperCase()).join(', '), r.attachmentMaxMb != null ? `max ${r.attachmentMaxMb} MB` : '']
      .filter(Boolean).join(', ');
    out.push(`Attachment needed${r.attachmentRequiredOverDays > 0 ? ` over ${dayText(r.attachmentRequiredOverDays)}` : ''}${what ? ` (${what})` : ''}`);
  }
  if (r.notNextTo.length) out.push(`Not next to ${r.notNextTo.join(', ')}`);
  if (r.sandwichEnabled) {
    out.push(`${SANDWICH_WHAT[r.sandwichAppliesTo] ?? SANDWICH_WHAT.BOTH} between leave days count as leave`
      + `${r.sandwichMinDays != null ? ` (leave of ${dayText(r.sandwichMinDays)} or more)` : ''}`
      + `${r.sandwichAcrossRequests ? ', also between separate requests' : ''}`);
  }
  return out;
}

/** The editor's limits as a summary, for {@link ruleLines}. */
export function summaryOf(l: PlanRuleLimits, typeName: (id: string) => string): LeaveRuleSummary {
  return {
    ...l,
    attachmentAllowedTypes: (l.attachmentAllowedTypes ?? '').split(',').filter(Boolean),
    notNextTo: l.notNextToTypeIds.map(typeName),
    hoursPerDay: 8,
  };
}

/** yyyy-mm-dd of a local date (no time-zone shift). */
export function isoDate(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** The default "effective from" of a change: the 1st of next month. */
export function firstOfNextMonth(today: Date): string {
  return isoDate(new Date(today.getFullYear(), today.getMonth() + 1, 1));
}


/** Days of an hourly request: hours / hours-per-day, to 2 decimals (as the server stores it). */
export function hourlyDays(hours: number, hoursPerDay: number): number {
  return Math.round((hours / hoursPerDay) * 100) / 100;
}

/** The file against the type's allowed extensions and size (10 MB when the plan sets none); null = fine. Mirrors the server. */
export function attachmentProblem(r: LeaveRuleSummary, typeName: string, fileName: string, sizeBytes: number): string | null {
  const ext = fileName.includes('.') ? fileName.slice(fileName.lastIndexOf('.') + 1).toLowerCase() : '';
  if (r.attachmentAllowedTypes.length && !r.attachmentAllowedTypes.includes(ext)) {
    return `${typeName} attachments must be ${r.attachmentAllowedTypes.map(e => e.toUpperCase()).join(', ')}.`;
  }
  const maxMb = r.attachmentMaxMb ?? 10;
  if (sizeBytes > maxMb * 1024 * 1024) return `${typeName} attachments can be at most ${maxMb} MB.`;
  return null;
}

/** The entitlement mode a type can have: Comp-Off is always earned-only; only regular paid leave can choose. */
export function modeFor(t: LeaveType, f: LimitsForm): EntitlementMode {
  if (t.code === 'COMP_OFF') return 'EARNED_ONLY';
  return t.classification === 'PAID' && t.entitlementBasis !== 'PER_OCCURRENCE' ? f.entitlementMode : 'QUOTA';
}

const shortDay = (iso: string) => {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });
};

/** LV-07: the preview's dates as two short lines — what is sandwiched and what isn't counted. */
export function previewNotes(p: LeavePreview): string[] {
  const label = (d: LeavePreview['days'][number]) => `${shortDay(d.date)}${d.note ? ` (${d.note})` : ''}`;
  const sandwiched = p.days.filter(d => d.kind === 'SANDWICHED');
  const skipped = p.days.filter(d => d.kind === 'WEEKLY_OFF' || d.kind === 'HOLIDAY');
  const out: string[] = [];
  if (sandwiched.length) out.push(`Counted as leave (sandwich): ${sandwiched.map(label).join(', ')}`);
  if (skipped.length) out.push(`Not counted: ${skipped.map(label).join(', ')}`);
  return out;
}
