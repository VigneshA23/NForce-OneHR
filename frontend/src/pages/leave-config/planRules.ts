/**
 * LV-01 plan editor logic, kept free of React so it can be unit tested: a plan version's type rules as
 * editable form rows (one per catalogue type, included or not), validation, and the request payload.
 */
import type {
  CarryForwardPriority, CreditFrequency, EligibilityConfig, LeaveType, PlanTypeRule, PlanTypeRulePayload,
} from '../../api/leave';

export const EMPTY_RULE_ELIGIBILITY: EligibilityConfig = {
  genders: [], departmentIds: [], locationIds: [], employmentTypes: [], includeEmployeeIds: [], excludeEmployeeIds: [],
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
          eligibility: r.eligibility ?? EMPTY_RULE_ELIGIBILITY,
        };
      }
      return {
        leaveTypeId: t.id, included: false, days: t.entitlementBasis === 'PER_OCCURRENCE' ? '182' : '15', creditFrequency: 'MONTHLY',
        carryForwardEnabled: false, carryForwardMaxDays: '', carryForwardExpiryMonths: '', carryForwardPriority: 'CARRY_FORWARD_FIRST',
        eligibility: EMPTY_RULE_ELIGIBILITY,
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
    if (paid && (f.days.trim() === '' || isNaN(daysN) || daysN < 0 || daysN > 999)) {
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
    rules.push({
      leaveTypeId: t.id,
      days: paid ? daysN : null,
      creditFrequency: annual ? f.creditFrequency : null,
      carryForwardEnabled: cf,
      carryForwardMaxDays: cf ? maxN : null,
      carryForwardExpiryMonths: cf ? expN : null,
      carryForwardPriority: f.carryForwardPriority,
      eligibility: f.eligibility,
    });
  }
  return { rules };
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
