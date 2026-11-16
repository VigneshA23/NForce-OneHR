// Pure parsing/validation for the "Import Investment Declaration" wizard (Keka restyle, §3.4).

export type InvestmentField = 'employeeCode' | 'financialYear' | 'section' | 'declaredAmount';

export const INVESTMENT_FIELD_LABEL: Record<InvestmentField, string> = {
  employeeCode: 'Employee Code', financialYear: 'Financial Year', section: 'Section', declaredAmount: 'Declared Amount',
};

export const INVESTMENT_REQUIRED_FIELDS: InvestmentField[] = ['employeeCode', 'financialYear', 'section', 'declaredAmount'];

export function guessInvestmentColumnMapping(headers: string[]): Partial<Record<InvestmentField, number>> {
  const mapping: Partial<Record<InvestmentField, number>> = {};
  const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, '');
  headers.forEach((h, i) => {
    const n = norm(h ?? '');
    if (mapping.employeeCode === undefined && (n.includes('employeecode') || n.includes('empcode') || n.includes('employeenumber'))) mapping.employeeCode = i;
    else if (mapping.financialYear === undefined && (n.includes('financialyear') || n === 'fy')) mapping.financialYear = i;
    else if (mapping.section === undefined && n.includes('section')) mapping.section = i;
    else if (mapping.declaredAmount === undefined && (n.includes('declaredamount') || n.includes('amount'))) mapping.declaredAmount = i;
  });
  return mapping;
}

export interface EmployeeLookup {
  userId: string;
  fullName: string;
}

export interface InvestmentParsedRow {
  rowNumber: number;
  employeeCodeRaw: string;
  financialYearRaw: string;
  sectionRaw: string;
  amountRaw: string;
  employeeId?: string;
  employeeName?: string;
  financialYear?: string;
  section?: string;
  declaredAmount?: number;
  error?: string;
}

function isBlankRow(a: string, b: string, c: string, d: string): boolean {
  return !a.trim() && !b.trim() && !c.trim() && !d.trim();
}

export function validateInvestmentRows(
  dataRows: string[][],
  mapping: Record<InvestmentField, number>,
  employeesByCode: Map<string, EmployeeLookup>,
): InvestmentParsedRow[] {
  const rows: InvestmentParsedRow[] = [];
  dataRows.forEach((row, idx) => {
    const employeeCodeRaw = String(row[mapping.employeeCode] ?? '').trim();
    const financialYearRaw = String(row[mapping.financialYear] ?? '').trim();
    const sectionRaw = String(row[mapping.section] ?? '').trim();
    const amountRaw = String(row[mapping.declaredAmount] ?? '').trim();
    if (isBlankRow(employeeCodeRaw, financialYearRaw, sectionRaw, amountRaw)) return;

    const base = { rowNumber: idx + 1, employeeCodeRaw, financialYearRaw, sectionRaw, amountRaw };
    const employee = employeesByCode.get(employeeCodeRaw.toLowerCase());
    if (!employee) {
      rows.push({ ...base, error: `Unknown employee code "${employeeCodeRaw}"` });
      return;
    }
    if (!financialYearRaw) {
      rows.push({ ...base, employeeId: employee.userId, employeeName: employee.fullName, error: 'Missing financial year' });
      return;
    }
    if (!sectionRaw) {
      rows.push({ ...base, employeeId: employee.userId, employeeName: employee.fullName, error: 'Missing section' });
      return;
    }
    const declaredAmount = Number(amountRaw.replace(/,/g, ''));
    if (!amountRaw || !Number.isFinite(declaredAmount) || declaredAmount < 0) {
      rows.push({ ...base, employeeId: employee.userId, employeeName: employee.fullName, financialYear: financialYearRaw, section: sectionRaw, error: `Invalid declared amount "${amountRaw}"` });
      return;
    }

    rows.push({ ...base, employeeId: employee.userId, employeeName: employee.fullName, financialYear: financialYearRaw, section: sectionRaw, declaredAmount });
  });
  return rows;
}
