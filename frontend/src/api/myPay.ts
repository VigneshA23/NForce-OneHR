import { API_ORIGIN } from './config';

export interface SalaryComponent { name: string; type: string; annual: number; monthly: number }
export interface SalaryRevision {
  id: string; structureName: string; effectiveFrom: string; effectiveTo: string | null;
  ctc: number; regularSalary: number; current: boolean; components: SalaryComponent[];
}
export interface SalaryTimeline { currentCtc: number | null; payCycle: string | null; frequency: string | null; revisions: SalaryRevision[] }

export interface PayslipLine { name: string; amount: number; ytd: number | null }
export interface PayslipPreview {
  companyName: string | null; companyAddress: string | null; periodLabel: string; cycleStart: string; cycleEnd: string;
  employeeName: string; employeeCode: string; joiningDate: string | null; department: string | null; designation: string | null;
  paymentMode: string; uan: string | null; pan: string | null; totalDays: number; lopDays: number; daysPayable: number;
  earnings: PayslipLine[]; contributions: PayslipLine[]; deductions: PayslipLine[];
  totalEarnings: number; totalDeductions: number; netPay: number;
}

export interface TaxSlabRow { from: number; to: number | null; rate: number; taxable: number; tax: number }
export interface TaxMonth { period: string; label: string; actual: boolean; gross: number; tds: number; cumulativeTds: number }
export interface IncomeTax {
  fyStartYear: number; fyLabel: string; regime: 'OLD' | 'NEW'; months: TaxMonth[];
  grossEarnings: number; previousEmployerIncome: number; standardDeduction: number; netTaxableIncome: number;
  tax: { slabTax: TaxSlabRow[]; slabTotal: number; rebate: number; grossTax: number; surcharge: number; cess: number; netPayable: number };
  taxPaidTillNow: number; remainingTax: number; configured: boolean;
}

async function get<T>(path: string, token: string): Promise<T> {
  const res = await fetch(`${API_ORIGIN}${path}`, { headers: { Authorization: `Bearer ${token}` } });
  if (!res.ok) {
    let msg = `Request failed (${res.status})`;
    try { msg = (await res.json()).message ?? msg; } catch { /* non-json */ }
    throw new Error(msg);
  }
  return res.json() as Promise<T>;
}

/** US-F07 / F08 / F09 — own data only; the server takes the user from the token. */
export const myPayApi = {
  salaryTimeline: (token: string) => get<SalaryTimeline>('/api/me/finances/salary-timeline', token),
  payslipPreview: (period: string, token: string) => get<PayslipPreview>(`/api/payslips/me/${period}/preview`, token),
  incomeTax: (token: string, fy?: number) => get<IncomeTax>(`/api/me/finances/income-tax${fy ? `?fy=${fy}` : ''}`, token),
};
