// Pure parsing/validation for the "Import Bonuses with Payout Date" wizard (Keka restyle, §3.4).
// Reuses salaryImportParsing's normalizeDate (same date-format rules, no reason to diverge) but
// is otherwise its own module — the field set and the "resolve a run from a date" step are
// specific to this import.

import { normalizeDate } from './salaryImportParsing';

export type BonusField = 'employeeCode' | 'amount' | 'payoutDate' | 'reason';

export const BONUS_FIELD_LABEL: Record<BonusField, string> = {
  employeeCode: 'Employee Code', amount: 'Bonus Amount', payoutDate: 'Payout Date', reason: 'Reason',
};

export const BONUS_REQUIRED_FIELDS: BonusField[] = ['employeeCode', 'amount', 'payoutDate'];
export const BONUS_ALL_FIELDS: BonusField[] = [...BONUS_REQUIRED_FIELDS, 'reason'];

export function guessBonusColumnMapping(headers: string[]): Partial<Record<BonusField, number>> {
  const mapping: Partial<Record<BonusField, number>> = {};
  const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, '');
  headers.forEach((h, i) => {
    const n = norm(h ?? '');
    if (mapping.employeeCode === undefined && (n.includes('employeecode') || n.includes('empcode') || n.includes('employeenumber'))) mapping.employeeCode = i;
    else if (mapping.payoutDate === undefined && n.includes('payout')) mapping.payoutDate = i;
    else if (mapping.amount === undefined && (n.includes('bonus') || n.includes('amount'))) mapping.amount = i;
    else if (mapping.reason === undefined && n.includes('reason')) mapping.reason = i;
  });
  return mapping;
}

export interface EmployeeLookup {
  userId: string;
  fullName: string;
}

export interface RunWindow {
  runId: string;
  cycleStart: string; // yyyy-mm-dd
  cycleEnd: string;
  status: string;
}

export interface BonusParsedRow {
  rowNumber: number;
  employeeCodeRaw: string;
  amountRaw: string;
  payoutDateRaw: string;
  employeeId?: string;
  employeeName?: string;
  amount?: number;
  payoutDate?: string;
  reason?: string;
  runId?: string;
  error?: string;
}

function isBlankRow(a: string, b: string, c: string): boolean {
  return !a.trim() && !b.trim() && !c.trim();
}

/** Resolving which run a payout date belongs to is the one step this import does that the
 * salary/financial imports don't — Keka ties a bonus to a payout DATE, not a specific run, but
 * every write this app can actually make (AdHocPaymentService#add) is run-scoped. A date with no
 * run covering it (that cycle's draft was never generated) is reported per-row rather than
 * queued anywhere — there's no "pending bonus" concept to hold it in. */
export function findRunForDate(runs: RunWindow[], date: string): RunWindow | undefined {
  return runs.find(r => r.cycleStart <= date && date <= r.cycleEnd);
}

export function validateBonusRows(
  dataRows: string[][],
  mapping: Record<'employeeCode' | 'amount' | 'payoutDate', number> & Partial<Record<'reason', number>>,
  employeesByCode: Map<string, EmployeeLookup>,
  runs: RunWindow[],
  defaultReason: string,
): BonusParsedRow[] {
  const rows: BonusParsedRow[] = [];
  dataRows.forEach((row, idx) => {
    const employeeCodeRaw = String(row[mapping.employeeCode] ?? '').trim();
    const amountRaw = String(row[mapping.amount] ?? '').trim();
    const payoutDateRaw = String(row[mapping.payoutDate] ?? '').trim();
    if (isBlankRow(employeeCodeRaw, amountRaw, payoutDateRaw)) return;

    const base = { rowNumber: idx + 1, employeeCodeRaw, amountRaw, payoutDateRaw };
    const reasonCol = mapping.reason;
    const reason = (reasonCol !== undefined ? String(row[reasonCol] ?? '').trim() : '') || defaultReason;

    const employee = employeesByCode.get(employeeCodeRaw.toLowerCase());
    if (!employee) {
      rows.push({ ...base, reason, error: `Unknown employee code "${employeeCodeRaw}"` });
      return;
    }

    const amount = Number(amountRaw.replace(/,/g, ''));
    if (!amountRaw || !Number.isFinite(amount) || amount <= 0) {
      rows.push({ ...base, employeeId: employee.userId, employeeName: employee.fullName, reason, error: `Invalid bonus amount "${amountRaw}"` });
      return;
    }

    const payoutDate = normalizeDate(payoutDateRaw);
    if (!payoutDate) {
      rows.push({ ...base, employeeId: employee.userId, employeeName: employee.fullName, amount, reason, error: `Invalid date "${payoutDateRaw}" — use yyyy-mm-dd or dd-mm-yyyy` });
      return;
    }

    if (!reason.trim()) {
      rows.push({ ...base, employeeId: employee.userId, employeeName: employee.fullName, amount, payoutDate, error: 'Reason is required' });
      return;
    }

    const run = findRunForDate(runs, payoutDate);
    if (!run) {
      rows.push({ ...base, employeeId: employee.userId, employeeName: employee.fullName, amount, payoutDate, reason, error: `No payroll run covers ${payoutDate} — generate that cycle's draft first` });
      return;
    }

    rows.push({ ...base, employeeId: employee.userId, employeeName: employee.fullName, amount, payoutDate, reason, runId: run.runId });
  });
  return rows;
}
