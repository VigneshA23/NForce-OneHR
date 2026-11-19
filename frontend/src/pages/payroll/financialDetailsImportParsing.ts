// Pure parsing/validation for the "Import Financial Information" wizard (Keka restyle, §3.4) —
// same split as salaryImportParsing.ts, kept separate since the two wizards' field sets are
// genuinely different, not a shared shape worth abstracting over.

export type FinancialField = 'employeeCode' | 'bankAccountNumber' | 'bankName' | 'bankIfsc' | 'panNumber' | 'uanNumber' | 'esiNumber';

export const FINANCIAL_FIELD_LABEL: Record<FinancialField, string> = {
  employeeCode: 'Employee Code', bankAccountNumber: 'Bank Account Number', bankName: 'Bank Name',
  bankIfsc: 'Bank IFSC', panNumber: 'PAN Number', uanNumber: 'UAN Number', esiNumber: 'ESI Number',
};

export type OptionalFinancialField = Exclude<FinancialField, 'employeeCode'>;

export const FINANCIAL_REQUIRED_FIELDS: FinancialField[] = ['employeeCode'];
export const FINANCIAL_OPTIONAL_FIELDS: OptionalFinancialField[] = ['bankAccountNumber', 'bankName', 'bankIfsc', 'panNumber', 'uanNumber', 'esiNumber'];
export const FINANCIAL_ALL_FIELDS: FinancialField[] = [...FINANCIAL_REQUIRED_FIELDS, ...FINANCIAL_OPTIONAL_FIELDS];

export function guessFinancialColumnMapping(headers: string[]): Partial<Record<FinancialField, number>> {
  const mapping: Partial<Record<FinancialField, number>> = {};
  const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, '');
  headers.forEach((h, i) => {
    const n = norm(h ?? '');
    if (mapping.employeeCode === undefined && (n.includes('employeecode') || n.includes('empcode') || n.includes('employeenumber'))) mapping.employeeCode = i;
    else if (mapping.bankIfsc === undefined && n.includes('ifsc')) mapping.bankIfsc = i;
    else if (mapping.bankAccountNumber === undefined && n.includes('bankaccount')) mapping.bankAccountNumber = i;
    else if (mapping.bankName === undefined && n.includes('bankname')) mapping.bankName = i;
    else if (mapping.panNumber === undefined && n.includes('pan')) mapping.panNumber = i;
    else if (mapping.uanNumber === undefined && n.includes('uan')) mapping.uanNumber = i;
    else if (mapping.esiNumber === undefined && n.includes('esi')) mapping.esiNumber = i;
  });
  return mapping;
}

export interface EmployeeLookup {
  userId: string;
  fullName: string;
}

export interface FinancialParsedRow {
  rowNumber: number;
  employeeCodeRaw: string;
  employeeId?: string;
  employeeName?: string;
  patch: Partial<Record<Exclude<FinancialField, 'employeeCode'>, string>>;
  error?: string;
}

export function validateFinancialRows(
  dataRows: string[][],
  mapping: Partial<Record<FinancialField, number>>,
  employeesByCode: Map<string, EmployeeLookup>,
): FinancialParsedRow[] {
  const rows: FinancialParsedRow[] = [];
  const employeeCodeCol = mapping.employeeCode;
  if (employeeCodeCol === undefined) return rows;

  dataRows.forEach((row, idx) => {
    const employeeCodeRaw = String(row[employeeCodeCol] ?? '').trim();
    const patch: FinancialParsedRow['patch'] = {};
    for (const field of FINANCIAL_OPTIONAL_FIELDS) {
      const col = mapping[field];
      if (col === undefined) continue;
      const value = String(row[col] ?? '').trim();
      if (value) patch[field] = value;
    }
    if (!employeeCodeRaw && Object.keys(patch).length === 0) return; // blank trailing row

    const base = { rowNumber: idx + 1, employeeCodeRaw, patch };
    if (!employeeCodeRaw) {
      rows.push({ ...base, error: 'Missing employee code' });
      return;
    }
    const employee = employeesByCode.get(employeeCodeRaw.toLowerCase());
    if (!employee) {
      rows.push({ ...base, error: `Unknown employee code "${employeeCodeRaw}"` });
      return;
    }
    if (Object.keys(patch).length === 0) {
      rows.push({ ...base, employeeId: employee.userId, employeeName: employee.fullName, error: 'No mapped field has a value for this row' });
      return;
    }
    rows.push({ ...base, employeeId: employee.userId, employeeName: employee.fullName });
  });
  return rows;
}
