import { describe, expect, it } from 'vitest';
import * as XLSX from 'xlsx';
import { OPENING_BALANCE_HEADERS, errorReportRows, parseUploadRows, templateSheetRows } from './openingBalances';

describe('opening balance spreadsheet (YE-02)', () => {
  const template = templateSheetRows([
    { employeeCode: 'EMP001', employeeName: 'Alice', leaveType: 'Paid Leave', usedDays: 4, currentCredited: 15, currentCarriedForward: 0 },
    { employeeCode: 'EMP002', employeeName: 'Bob', leaveType: 'Paid Leave', usedDays: 0, currentCredited: 0, currentCarriedForward: 0 },
  ]);

  it('the template has every column, with the "New" columns and Reason blank', () => {
    expect(Object.keys(template[0])).toEqual([...OPENING_BALANCE_HEADERS]);
    expect(template[0]['New Credited']).toBe('');
    expect(template[0]['Current Credited']).toBe(15);
  });

  it('reads a filled-in template back through SheetJS, with Excel row numbers and blank = unchanged', () => {
    template[1]['New Credited'] = 7.5;
    template[1]['Reason'] = 'Joined mid-year';
    const ws = XLSX.utils.json_to_sheet(template, { header: [...OPENING_BALANCE_HEADERS] });
    const json = XLSX.utils.sheet_to_json<Record<string, unknown>>(ws, { defval: '' });

    const rows = parseUploadRows(json)!;

    expect(rows).toHaveLength(2);
    expect(rows[0]).toEqual({ rowNumber: 2, employeeCode: 'EMP001', leaveType: 'Paid Leave', newCredited: null, newCarriedForward: null, reason: null });
    expect(rows[1]).toEqual({ rowNumber: 3, employeeCode: 'EMP002', leaveType: 'Paid Leave', newCredited: '7.5', newCarriedForward: null, reason: 'Joined mid-year' });
  });

  it('refuses a sheet whose columns are not the template', () => {
    expect(parseUploadRows([{ Code: 'EMP001', Days: 3 }])).toBeNull();
  });

  it('the error report lists only the failing rows, with what was uploaded and why', () => {
    const report = errorReportRows([
      { rowNumber: 2, employeeCode: 'EMP001', employeeName: 'Alice', leaveType: 'Paid Leave', currentCredited: 15, newCredited: 15,
        currentCarriedForward: 0, newCarriedForward: 0, changed: false, errors: [], warnings: [] },
      { rowNumber: 3, employeeCode: 'EMP999', employeeName: null, leaveType: 'Paid Leave', currentCredited: 0, newCredited: 0,
        currentCarriedForward: 0, newCarriedForward: 0, changed: false, errors: ['No active employee with code EMP999', 'Reason is required for a change'], warnings: [] },
    ], [{ rowNumber: 3, employeeCode: 'EMP999', leaveType: 'Paid Leave', newCredited: '5', newCarriedForward: null, reason: null }]);

    expect(report).toEqual([{ Row: 3, 'Employee Code': 'EMP999', 'Leave Type': 'Paid Leave', 'New Credited': '5', 'New Carried Forward': '',
      Reason: '', Errors: 'No active employee with code EMP999; Reason is required for a change' }]);
  });
});
