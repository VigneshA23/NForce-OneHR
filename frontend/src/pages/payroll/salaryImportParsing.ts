// Pure parsing/validation for the "Import Salaries with Effective Date" wizard (Keka restyle,
// §3.4) — extracted from the wizard component so it's unit-testable without React/DOM, same
// precedent as ReportsPage.tsx's computeHeadcountSummary.

export type RequiredField = 'employeeCode' | 'ctc' | 'effectiveFrom';

export const REQUIRED_FIELD_LABEL: Record<RequiredField, string> = {
  employeeCode: 'Employee Code', ctc: 'New CTC', effectiveFrom: 'Effective From',
};

export const REQUIRED_FIELDS: RequiredField[] = ['employeeCode', 'ctc', 'effectiveFrom'];

/** Best-effort guess at which uploaded column is which required field, by normalized header
 * text — never authoritative; the wizard's "Match Columns" step always shows the guess for the
 * admin to confirm or correct, exactly like Keka's own wizard does. */
export function guessColumnMapping(headers: string[]): Partial<Record<RequiredField, number>> {
  const mapping: Partial<Record<RequiredField, number>> = {};
  const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, '');
  headers.forEach((h, i) => {
    const n = norm(h ?? '');
    if (mapping.employeeCode === undefined && (n.includes('employeecode') || n.includes('empcode') || n.includes('employeenumber') || n === 'employeeid')) {
      mapping.employeeCode = i;
    } else if (mapping.ctc === undefined && (n.includes('ctc') || n.includes('newsalary') || n.includes('newctc') || n.includes('annualctc'))) {
      mapping.ctc = i;
    } else if (mapping.effectiveFrom === undefined && (n.includes('effective') || n.includes('date'))) {
      mapping.effectiveFrom = i;
    }
  });
  return mapping;
}

/** Accepts ISO (yyyy-mm-dd, what Excel date cells normalize to when read with a dateNF of that
 * format) or dd-mm-yyyy / dd/mm/yyyy (the regional convention this app's own date pickers already
 * use) — never mm/dd/yyyy, which would silently misread one against the other for any day <= 12. */
export function normalizeDate(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  if (/^\d{4}-\d{2}-\d{2}$/.test(trimmed)) return trimmed;
  const dmy = /^(\d{1,2})[-/](\d{1,2})[-/](\d{4})$/.exec(trimmed);
  if (dmy) {
    const day = Number(dmy[1]);
    const month = Number(dmy[2]);
    const year = dmy[3];
    if (day >= 1 && day <= 31 && month >= 1 && month <= 12) {
      return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
    }
  }
  return null;
}

export interface EmployeeLookup {
  userId: string;
  fullName: string;
}

export interface ParsedRow {
  rowNumber: number; // 1-based, header excluded
  employeeCodeRaw: string;
  ctcRaw: string;
  effectiveFromRaw: string;
  employeeId?: string;
  employeeName?: string;
  ctc?: number;
  effectiveFrom?: string;
  error?: string;
}

/** A row with every required field blank is a trailing empty spreadsheet row, not a mistake —
 * silently dropped rather than reported as an error. */
function isBlankRow(a: string, b: string, c: string): boolean {
  return !a.trim() && !b.trim() && !c.trim();
}

export function validateRows(
  dataRows: string[][],
  mapping: Record<RequiredField, number>,
  employeesByCode: Map<string, EmployeeLookup>,
): ParsedRow[] {
  const rows: ParsedRow[] = [];
  dataRows.forEach((row, idx) => {
    const employeeCodeRaw = String(row[mapping.employeeCode] ?? '').trim();
    const ctcRaw = String(row[mapping.ctc] ?? '').trim();
    const effectiveFromRaw = String(row[mapping.effectiveFrom] ?? '').trim();
    if (isBlankRow(employeeCodeRaw, ctcRaw, effectiveFromRaw)) return;

    const base = { rowNumber: idx + 1, employeeCodeRaw, ctcRaw, effectiveFromRaw };
    const employee = employeesByCode.get(employeeCodeRaw.toLowerCase());
    if (!employee) {
      rows.push({ ...base, error: `Unknown employee code "${employeeCodeRaw}"` });
      return;
    }

    const ctc = Number(ctcRaw.replace(/,/g, ''));
    if (!ctcRaw || !Number.isFinite(ctc) || ctc <= 0) {
      rows.push({ ...base, employeeId: employee.userId, employeeName: employee.fullName, error: `Invalid CTC "${ctcRaw}"` });
      return;
    }

    const effectiveFrom = normalizeDate(effectiveFromRaw);
    if (!effectiveFrom) {
      rows.push({ ...base, employeeId: employee.userId, employeeName: employee.fullName, ctc, error: `Invalid date "${effectiveFromRaw}" — use yyyy-mm-dd or dd-mm-yyyy` });
      return;
    }

    rows.push({ ...base, employeeId: employee.userId, employeeName: employee.fullName, ctc, effectiveFrom });
  });
  return rows;
}
