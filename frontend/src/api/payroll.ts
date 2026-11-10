import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/payroll`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

function bearerOnly(token: string) {
  return { Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error((body as any).message ?? `Request failed (${res.status})`);
  return body as T;
}

export interface PayGroupResponse {
  id: string;
  name: string;
  legalEntity: string;
  frequency: string;
  currency: string;
  cycleStartDay: number;
  cycleEndDay: number;
  processingDay: number;
  active: boolean;
}

export type PayrollRunStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'DISBURSED';

export interface PayrollRunResponse {
  id: string;
  payGroupId: string;
  cycleStart: string;
  cycleEnd: string;
  status: PayrollRunStatus;
  employeeCount: number;
  totalGross: number;
  totalDeductions: number;
  totalNet: number;
  submittedAt: string | null;
  approvedAt: string | null;
  disbursedAt: string | null;
  lastRolledBackAt: string | null;
  lastRollbackReason: string | null;
  approvalStages: string[] | null;
  pendingApprovalStage: string | null;
  createdAt: string;
}

export interface PayslipLineItemResponse {
  componentId: string;
  componentName: string;
  componentType: string;
  amount: number;
  prorated: boolean;
}

export interface PayslipResponse {
  id: string;
  payrollRunId: string;
  employeeId: string;
  ctc: number;
  cycleDays: number;
  lopDays: number;
  employedDays: number;
  prorationFactor: number;
  grossPay: number;
  totalDeductions: number;
  netPay: number;
  released: boolean;
  paymentWithheld: boolean;
  lineItems: PayslipLineItemResponse[];
}

export interface PayrollLopCalculationResponse {
  id: string;
  employeeId: string;
  cycleStart: string;
  cycleEnd: string;
  unpaidLeaveDays: number;
  penaltyLopDays: number;
  autoLopNoAttendanceDays: number;
  computedLopDays: number;
  needsManualReviewDates: string[];
  computedAt: string;
  overrideLopDays: number | null;
  overrideReason: string | null;
  overriddenBy: string | null;
  overriddenAt: string | null;
  effectiveLopDays: number;
}

export type ReviewStepType = 'LEAVE_APPLIED' | 'NO_ATTENDANCE';
export type ReviewDecision = 'PENDING' | 'APPROVED' | 'REJECTED' | 'MARK_LOP' | 'DEDUCT_FROM_LEAVE' | 'NO_ACTION';

export interface PayrollRunReviewItemResponse {
  id: string;
  payrollRunId: string;
  employeeId: string;
  employeeName: string;
  stepType: ReviewStepType;
  sourceType: string | null;
  sourceId: string | null;
  itemDate: string;
  days: number;
  leaveTypeName: string | null;
  decision: ReviewDecision;
  selectedLeaveTypeId: string | null;
  comment: string | null;
  decidedByName: string | null;
  decidedAt: string | null;
}

export interface BulkDecideLeaveReviewResult {
  succeededIds: string[];
  failed: { itemId: string; reason: string }[];
}

export interface AdHocPaymentResponse {
  id: string;
  payrollRunId: string;
  employeeId: string;
  componentId: string;
  componentName: string;
  componentType: string;
  amount: number;
  reason: string;
  addedByName: string;
  addedAt: string;
}

export interface AdHocDeductionResponse {
  id: string;
  employeeId: string;
  componentId: string;
  componentName: string;
  componentType: string;
  totalAmount: number;
  installmentAmount: number;
  remainingBalance: number;
  status: string;
  reason: string;
  createdByName: string;
  createdAt: string;
}

export interface AdHocDeductionInstallmentResponse {
  id: string;
  payrollRunId: string;
  amount: number;
  appliedAt: string;
}

export type SalaryHoldType = 'PROCESSING' | 'PAYOUT';

export interface SalaryHoldResponse {
  id: string;
  payrollRunId: string;
  employeeId: string;
  reason: string;
  holdType: SalaryHoldType;
  status: string;
  createdByName: string;
  createdAt: string;
  releasedByName: string | null;
  releasedAt: string | null;
}

export interface SalaryComponentCatalogResponse {
  id: string;
  name: string;
  type: string;
  taxable: boolean;
  taxSection: string | null;
  includedInCtc: boolean;
  shownOnPayslip: boolean;
  prorateOnLop: boolean;
  active: boolean;
}

export const payrollApi = {
  // Pay groups (read-only picker for this slice)
  listPayGroups: (token: string) =>
    fetch(`${BASE}/pay-groups`, { headers: bearerOnly(token) }).then(handle<PayGroupResponse[]>),

  // Salary component catalog (for ad-hoc payment/deduction pickers)
  listSalaryComponents: (token: string) =>
    fetch(`${BASE}/salary-components`, { headers: bearerOnly(token) }).then(handle<SalaryComponentCatalogResponse[]>),

  // Runs
  generateDraft: (payload: { payGroupId: string; referenceDate?: string }, token: string) =>
    fetch(`${BASE}/runs/generate`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<PayrollRunResponse>),

  listRuns: (payGroupId: string, token: string) =>
    fetch(`${BASE}/runs?${new URLSearchParams({ payGroupId }).toString()}`, { headers: bearerOnly(token) }).then(handle<PayrollRunResponse[]>),

  getRun: (runId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}`, { headers: bearerOnly(token) }).then(handle<PayrollRunResponse>),

  submit: (runId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/submit`, { method: 'POST', headers: authHeaders(token) }).then(handle<PayrollRunResponse>),

  approve: (runId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/approve`, { method: 'POST', headers: authHeaders(token) }).then(handle<PayrollRunResponse>),

  disburse: (runId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/disburse`, { method: 'POST', headers: authHeaders(token) }).then(handle<PayrollRunResponse>),

  rollbackRun: (runId: string, reason: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/rollback`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ reason }) }).then(handle<PayrollRunResponse>),

  // Payslips
  listPayslips: (runId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/payslips`, { headers: bearerOnly(token) }).then(handle<PayslipResponse[]>),

  getPayslip: (runId: string, employeeId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/payslips/${employeeId}`, { headers: bearerOnly(token) }).then(handle<PayslipResponse>),

  rollbackEmployeePayslip: (runId: string, employeeId: string, reason: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/payslips/${employeeId}/rollback`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ reason }) }).then(handle<PayslipResponse>),

  // LOP
  recomputeLop: (payload: { employeeId: string; payGroupId: string; referenceDate?: string; runId?: string }, token: string) =>
    fetch(`${BASE}/lop/recompute`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<PayrollLopCalculationResponse>),

  overrideLop: (payload: { employeeId: string; payGroupId: string; referenceDate?: string; overrideLopDays?: number | null; overrideReason: string }, token: string) =>
    fetch(`${BASE}/lop/override`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<PayrollLopCalculationResponse>),

  getLop: (employeeId: string, payGroupId: string, token: string, referenceDate?: string) => {
    const params = new URLSearchParams({ payGroupId });
    if (referenceDate) params.set('referenceDate', referenceDate);
    return fetch(`${BASE}/lop/${employeeId}?${params.toString()}`, { headers: bearerOnly(token) }).then(handle<PayrollLopCalculationResponse>);
  },

  // US-C22 — Leave Applied review
  listLeaveAppliedReview: (runId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/review/leave-applied`, { headers: bearerOnly(token) }).then(handle<PayrollRunReviewItemResponse[]>),

  bulkDecideLeaveApplied: (runId: string, payload: { itemIds: string[]; decision: 'APPROVED' | 'REJECTED'; reason?: string }, token: string) =>
    fetch(`${BASE}/runs/${runId}/review/leave-applied/decide`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<BulkDecideLeaveReviewResult>),

  // US-C23 — No Attendance review
  listNoAttendanceReview: (runId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/review/no-attendance`, { headers: bearerOnly(token) }).then(handle<PayrollRunReviewItemResponse[]>),

  decideNoAttendance: (runId: string, itemId: string, payload: { action: 'DEDUCT_FROM_LEAVE' | 'MARK_LOP' | 'NO_ACTION'; selectedLeaveTypeId?: string; comment?: string }, token: string) =>
    fetch(`${BASE}/runs/${runId}/review/no-attendance/${itemId}/decide`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<PayrollRunReviewItemResponse>),

  // US-C24 — Ad-hoc payments (run-scoped)
  listAdHocPayments: (runId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/ad-hoc-payments`, { headers: bearerOnly(token) }).then(handle<AdHocPaymentResponse[]>),

  addAdHocPayment: (runId: string, payload: { employeeId: string; componentName: string; amount: number; reason: string }, token: string) =>
    fetch(`${BASE}/runs/${runId}/ad-hoc-payments`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<AdHocPaymentResponse>),

  removeAdHocPayment: (runId: string, paymentId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/ad-hoc-payments/${paymentId}`, { method: 'DELETE', headers: authHeaders(token) }).then(handle<void>),

  // US-C25 — Ad-hoc deductions (employee-scoped, not run-scoped)
  listAdHocDeductionsByEmployee: (employeeId: string, token: string) =>
    fetch(`${BASE}/ad-hoc-deductions?${new URLSearchParams({ employeeId }).toString()}`, { headers: bearerOnly(token) }).then(handle<AdHocDeductionResponse[]>),

  createAdHocDeduction: (payload: { employeeId: string; componentName: string; totalAmount: number; installmentAmount: number; reason: string }, token: string) =>
    fetch(`${BASE}/ad-hoc-deductions`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<AdHocDeductionResponse>),

  cancelAdHocDeduction: (deductionId: string, token: string) =>
    fetch(`${BASE}/ad-hoc-deductions/${deductionId}/cancel`, { method: 'POST', headers: authHeaders(token) }).then(handle<AdHocDeductionResponse>),

  listAdHocDeductionInstallments: (deductionId: string, token: string) =>
    fetch(`${BASE}/ad-hoc-deductions/${deductionId}/installments`, { headers: bearerOnly(token) }).then(handle<AdHocDeductionInstallmentResponse[]>),

  // US-C26 — Salary holds (run-scoped)
  listSalaryHolds: (runId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/salary-holds`, { headers: bearerOnly(token) }).then(handle<SalaryHoldResponse[]>),

  createSalaryHold: (runId: string, payload: { employeeId: string; reason: string; type: SalaryHoldType }, token: string) =>
    fetch(`${BASE}/runs/${runId}/salary-holds`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<SalaryHoldResponse>),

  releaseSalaryHold: (runId: string, holdId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/salary-holds/${holdId}/release`, { method: 'POST', headers: authHeaders(token) }).then(handle<SalaryHoldResponse>),
};
