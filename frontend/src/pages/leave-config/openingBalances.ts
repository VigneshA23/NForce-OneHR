// YE-02: the opening-balance spreadsheet — what the template holds, how an upload is read back, and
// the row-level error report. Pure functions over plain rows; the component does the SheetJS I/O.
import type { OpeningBalanceRowResult, OpeningBalanceTemplateRow, OpeningBalanceUploadRow } from '../../api/leave';

export const OPENING_BALANCE_SHEET = 'Opening Balances';

export const OPENING_BALANCE_HEADERS = [
  'Employee Code', 'Employee Name', 'Leave Type', 'Used',
  'Current Credited', 'New Credited', 'Current Carried Forward', 'New Carried Forward', 'Reason',
] as const;

/** Spreadsheet rows of the template: every column present, the "New" columns and Reason left blank for HR. */
export function templateSheetRows(rows: OpeningBalanceTemplateRow[]): Record<string, string | number>[] {
  return rows.map(r => ({
    'Employee Code': r.employeeCode,
    'Employee Name': r.employeeName,
    'Leave Type': r.leaveType,
    Used: Number(r.usedDays),
    'Current Credited': Number(r.currentCredited),
    'New Credited': '',
    'Current Carried Forward': Number(r.currentCarriedForward),
    'New Carried Forward': '',
    Reason: '',
  }));
}

export const OPENING_BALANCE_INSTRUCTIONS: string[][] = [
  ['How to use this file'],
  ['1. Fill "New Credited" and/or "New Carried Forward" only where a figure should change. Leave a cell blank to keep the current value.'],
  ['2. "Credited" is the leave credited so far this year. For monthly-credited leave, credits still to come are added on top of it.'],
  ['3. "Carried Forward" is the separate bucket of days brought from the previous year (it can expire). If automatic carry-forward is on for the leave type, the year-end job adds the unused days on top of this on 1 January.'],
  ['4. Values are whole or half days (e.g. 7.5). New Credited cannot be below "Used".'],
  ['5. A Reason is required on every row you change. It is kept in the audit log.'],
  ['6. Rows you leave unchanged are ignored, so uploading the same file twice gives the same result.'],
  ['7. Do not rename the columns. Delete rows you do not need, or leave them unchanged.'],
];

function cell(v: unknown): string | null {
  if (v == null) return null;
  const s = String(v).trim();
  return s === '' ? null : s;
}

/**
 * Rows read back from the uploaded sheet (SheetJS `sheet_to_json` objects keyed by header) -> the
 * upload payload. `rowNumber` is the Excel row (header on row 1, so data starts on row 2). Blank rows
 * are skipped. Returns null when the headers don't match the template.
 */
export function parseUploadRows(json: Record<string, unknown>[]): OpeningBalanceUploadRow[] | null {
  if (json.length > 0) {
    const keys = new Set(Object.keys(json[0]).map(k => k.trim()));
    for (const h of ['Employee Code', 'Leave Type', 'New Credited', 'New Carried Forward', 'Reason']) {
      if (!keys.has(h)) return null;
    }
  }
  const out: OpeningBalanceUploadRow[] = [];
  json.forEach((row, i) => {
    const get = (h: string) => cell(row[h] ?? row[` ${h}`] ?? row[`${h} `]);
    const code = get('Employee Code');
    const type = get('Leave Type');
    if (!code && !type && !get('New Credited') && !get('New Carried Forward')) return;
    out.push({
      rowNumber: Number((row as { __rowNum__?: number }).__rowNum__ ?? i + 1) + 1,
      employeeCode: code ?? '',
      leaveType: type ?? '',
      newCredited: get('New Credited'),
      newCarriedForward: get('New Carried Forward'),
      reason: get('Reason'),
    });
  });
  return out;
}

/** The downloadable row-level report: each problem row with what was uploaded and why it failed. */
export function errorReportRows(results: OpeningBalanceRowResult[], upload: OpeningBalanceUploadRow[]): Record<string, string | number>[] {
  const byRow = new Map(upload.map(u => [u.rowNumber, u]));
  return results.filter(r => r.errors.length > 0).map(r => {
    const u = byRow.get(r.rowNumber);
    return {
      Row: r.rowNumber,
      'Employee Code': r.employeeCode,
      'Leave Type': r.leaveType,
      'New Credited': u?.newCredited ?? '',
      'New Carried Forward': u?.newCarriedForward ?? '',
      Reason: u?.reason ?? '',
      Errors: r.errors.join('; '),
    };
  });
}
