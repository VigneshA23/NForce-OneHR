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
  address: string | null;
  frequency: string;
  currency: string;
  cycleStartDay: number;
  cycleEndDay: number;
  processingDay: number;
  active: boolean;
}

// Keka restyle — Filing Details (§3.8).
export interface PayGroupFilingDetailsResponse {
  payGroupId: string;
  pan: string | null;
  tan: string | null;
  tanCircleNumber: string | null;
  citTdsLocation: string | null;
  form130Signatory: string | null;
  signatoryDesignation: string | null;
  signatoryFatherName: string | null;
  pfEstablishmentId: string | null;
  esiEstablishmentId: string | null;
  updatedAt: string | null;
}

// Keka restyle — Import Financial Information (§3.4).
export interface EmployeeFinancialDetailsResponse {
  employeeId: string;
  employeeCode: string;
  employeeName: string;
  bankAccountNumber: string | null;
  bankName: string | null;
  bankIfsc: string | null;
  panNumber: string | null;
  uanNumber: string | null;
  esiNumber: string | null;
}

// Keka restyle — Configure Payslip Settings (§3.9).
export type PayslipFieldKey = 'dateOfJoining' | 'department' | 'designation' | 'uan' | 'esi' | 'pan';

export interface PayslipSettingsResponse {
  payGroupId: string;
  passwordProtected: boolean;
  documentHeaderText: string | null;
  footerText: string | null;
  hasLogo: boolean;
  logoAlignment: 'LEFT' | 'RIGHT';
  excludeNaFields: boolean;
  earningsHeading: string | null;
  deductionsHeading: string | null;
  includeLeaveSummary: boolean;
  includeArrearBreakup: boolean;
  includeYtdColumn: boolean;
  includeEmployerContributions: boolean;
  payslipFields: PayslipFieldKey[];
}

export interface PtRegisteredLocationResponse {
  id: string;
  payGroupId: string;
  stateName: string;
  locationName: string;
  establishmentId: string | null;
  registrationDate: string | null;
  signatory: string | null;
  signatoryDesignation: string | null;
  signatoryFatherName: string | null;
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
  lastRolledBackBy: string | null;
  approvalStages: string[] | null;
  pendingApprovalStage: string | null;
  createdAt: string;
}

export interface PayrollRunRollbackResponse {
  id: string;
  reason: string;
  rolledBackBy: string | null;
  rolledBackAt: string;
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

export interface BulkAdHocPaymentResult {
  added: AdHocPaymentResponse[];
  skippedCount: number;
}

export interface RecurringAdHocPaymentRuleResponse {
  id: string;
  payGroupId: string | null;
  payGroupName: string;
  componentName: string;
  componentType: string;
  amount: number;
  reason: string;
  active: boolean;
  createdByName: string;
  createdAt: string;
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

export interface SalaryComponentRequest {
  componentName: string;
  calcType: 'FIXED' | 'PERCENT_OF_GROSS' | 'PERCENT_OF_COMPONENT' | 'BALANCING_REMAINDER';
  value?: number | null;
  referenceComponentName?: string | null;
  cap?: number | null;
  useLowerOfCap?: boolean;
}

export interface SalaryComponentResponse {
  id: string;
  componentId: string;
  componentName: string;
  type: string;
  taxable: boolean;
  includedInCtc: boolean;
  shownOnPayslip: boolean;
  prorateOnLop: boolean;
  calcType: string;
  value: number | null;
  referenceComponentName: string | null;
  cap: number | null;
  useLowerOfCap: boolean;
}

export interface SalaryStructureResponse {
  id: string;
  name: string;
  grade: string | null;
  financialYear: string | null;
  components: SalaryComponentResponse[];
  createdAt: string;
}

export interface EmployeeCompensationResponse {
  id: string;
  employeeId: string;
  payGroupId: string;
  structureId: string;
  structureName: string;
  ctc: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  createdAt: string;
}

export interface MyCtcComponentResponse {
  componentName: string;
  componentType: string;
  annualAmount: number;
  monthlyAmount: number;
}

// Keka restyle — "Current Salary Structure" report (§3.6): the org-wide version of the My
// Finances CTC breakdown, one row per employee.
export interface CurrentSalaryStructureRowResponse {
  employeeId: string;
  structureId: string;
  structureName: string;
  ctc: number;
  effectiveFrom: string;
  components: MyCtcComponentResponse[];
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
  displayOrder: number;
  accountingCode: string | null;
}

export interface DepartmentCostResponse {
  departmentId: string | null;
  departmentName: string;
  employeeCount: number;
  grossPay: number;
  totalDeductions: number;
  netPay: number;
}

export interface ProjectCostResponse {
  projectId: string | null;
  projectCode: string | null;
  projectName: string;
  grossPay: number;
  totalDeductions: number;
  netPay: number;
}

export interface PayrollCostReportResponse {
  runId: string;
  cycleStart: string;
  cycleEnd: string;
  employeeCount: number;
  grossPay: number;
  totalDeductions: number;
  netPay: number;
  byDepartment: DepartmentCostResponse[];
  byProject: ProjectCostResponse[];
}

// US-C13 — opaque, version-dated statutory config (PF/ESI/PT/TDS slabs etc.). Phase 2 only stores
// and versions it; nothing in payroll-run generation reads slabConfig yet (see payroll-no-hardcoded-
// values memory — this is the HR-editable config layer that replaces hardcoded Java constants, once
// a later phase wires computation up to it).
export interface EmployeeDateEntry {
  employeeId: string;
  employeeName: string;
  date: string;
}

export interface PayrollJoinersExitsResponse {
  runId: string;
  cycleStart: string;
  cycleEnd: string;
  joiners: EmployeeDateEntry[];
  exits: EmployeeDateEntry[];
}

export interface PayrollClaimResponse {
  id: string;
  employeeId: string;
  employeeName: string;
  categoryName: string;
  amount: number;
  expenseDate: string;
  status: string;
}

export interface StatutoryOverrideResponse {
  id: string;
  employeeId: string;
  country: string;
  ruleType: string;
  cycleStart: string;
  cycleEnd: string;
  overrideAmount: number;
  overrideReason: string;
  overriddenBy: string;
  overriddenAt: string;
}

export interface StatutoryPreviewResponse {
  computedAmount: number | null;
  note: string | null;
}

export interface ArrearsResponse {
  id: string;
  employeeId: string;
  employeeName: string;
  amount: number;
  reason: string;
  sourceType: string;
  sourceCycleStart: string;
  sourceCycleEnd: string;
  status: string;
  appliedToRunId: string | null;
  createdAt: string;
}

export interface StatutoryRuleResponse {
  id: string;
  country: string;
  ruleType: string;
  slabConfig: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  createdAt: string;
}

export interface LocationCountryMismatchResponse {
  locationId: string;
  locationName: string;
  country: string | null;
  employeeCount: number;
}

export const payrollApi = {
  // Pay groups (read-only picker for this slice)
  listPayGroups: (token: string) =>
    fetch(`${BASE}/pay-groups`, { headers: bearerOnly(token) }).then(handle<PayGroupResponse[]>),

  // Salary component catalog (for ad-hoc payment/deduction pickers)
  listSalaryComponents: (token: string) =>
    fetch(`${BASE}/salary-components`, { headers: bearerOnly(token) }).then(handle<SalaryComponentCatalogResponse[]>),

  // US-C01 — Salary Structures
  listSalaryStructures: (token: string) =>
    fetch(`${BASE}/salary-structures`, { headers: bearerOnly(token) }).then(handle<SalaryStructureResponse[]>),

  createSalaryStructure: (payload: { name: string; grade?: string; financialYear?: string; components: SalaryComponentRequest[] }, token: string) =>
    fetch(`${BASE}/salary-structures`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<SalaryStructureResponse>),

  updateSalaryStructure: (id: string, payload: { name: string; grade?: string; financialYear?: string; components: SalaryComponentRequest[] }, token: string) =>
    fetch(`${BASE}/salary-structures/${id}`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<SalaryStructureResponse>),

  // US-C02 — Assign/revise an employee's compensation
  assignCompensation: (payload: { employeeId: string; payGroupId: string; structureId: string; ctc: number; effectiveFrom: string }, token: string) =>
    fetch(`${BASE}/employee-compensation`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<EmployeeCompensationResponse>),

  getCurrentCompensation: (employeeId: string, token: string) =>
    fetch(`${BASE}/employee-compensation/employee/${employeeId}/current`, { headers: bearerOnly(token) }).then(handle<EmployeeCompensationResponse>),

  getCompensationHistory: (employeeId: string, token: string) =>
    fetch(`${BASE}/employee-compensation/employee/${employeeId}/history`, { headers: bearerOnly(token) }).then(handle<EmployeeCompensationResponse[]>),

  // Org-wide "who was revised when" admin view — every compensation row across every employee.
  // Pair with auditApi (action: 'EMPLOYEE_COMPENSATION_ASSIGNED') to find who made each change and
  // when; this endpoint itself carries no actor/timestamp of its own (see the backend service's
  // own comment for why).
  listAllCompensationHistory: (token: string) =>
    fetch(`${BASE}/employee-compensation/history`, { headers: bearerOnly(token) }).then(handle<EmployeeCompensationResponse[]>),

  // Keka restyle — "Current CTC Report" (§3.6 Reports Home). asOf defaults to today server-side.
  listAllCurrentCompensation: (token: string, asOf?: string) =>
    fetch(`${BASE}/employee-compensation/current${asOf ? `?asOf=${asOf}` : ''}`, { headers: bearerOnly(token) }).then(handle<EmployeeCompensationResponse[]>),

  // Keka restyle — "Employee CTC Report" (§3.6), a chosen date range across every employee.
  listCompensationBetween: (start: string, end: string, token: string) =>
    fetch(`${BASE}/employee-compensation/between?${new URLSearchParams({ start, end }).toString()}`, { headers: bearerOnly(token) }).then(handle<EmployeeCompensationResponse[]>),

  // Keka restyle — "Current Salary Structure" report (§3.6): every employee's current per-
  // component breakup.
  listAllCurrentSalaryStructure: (token: string, asOf?: string) =>
    fetch(`${BASE}/employee-compensation/current/breakdown${asOf ? `?asOf=${asOf}` : ''}`, { headers: bearerOnly(token) }).then(handle<CurrentSalaryStructureRowResponse[]>),

  // US-C02 self-service follow-up — "me" resolved server-side from the JWT, no employeeId needed.
  // Returns null (not a thrown error) when nothing's been assigned yet — a real, expected state for
  // this endpoint specifically, unlike every other call here where a 404 means something's wrong.
  getMyCurrentCompensation: async (token: string): Promise<EmployeeCompensationResponse | null> => {
    const res = await fetch(`${BASE}/employee-compensation/me/current`, { headers: bearerOnly(token) });
    if (res.status === 404) return null;
    return handle<EmployeeCompensationResponse>(res);
  },

  getMyCompensationHistory: (token: string) =>
    fetch(`${BASE}/employee-compensation/me/history`, { headers: bearerOnly(token) }).then(handle<EmployeeCompensationResponse[]>),

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

  rollbackHistory: (runId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/rollback-history`, { headers: bearerOnly(token) }).then(handle<PayrollRunRollbackResponse[]>),

  // Payslips
  listPayslips: (runId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/payslips`, { headers: bearerOnly(token) }).then(handle<PayslipResponse[]>),

  getPayslip: (runId: string, employeeId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/payslips/${employeeId}`, { headers: bearerOnly(token) }).then(handle<PayslipResponse>),

  rollbackEmployeePayslip: (runId: string, employeeId: string, reason: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/payslips/${employeeId}/rollback`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ reason }) }).then(handle<PayslipResponse>),

  /** Opens the employee's actual rendered payslip PDF in a new tab — same document My Finances
   * would show them, viewed from the run console instead. */
  viewPayslipPdf: async (runId: string, employeeId: string, token: string) => {
    const res = await fetch(`${BASE}/runs/${runId}/payslips/${employeeId}/pdf`, { headers: bearerOnly(token) });
    if (!res.ok) throw new Error(`Failed to open payslip (${res.status})`);
    const url = URL.createObjectURL(await res.blob());
    window.open(url, '_blank');
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
  },

  /** Centralized "Distribute Payslips" — one call for the whole run (or an explicit subset),
   * never a per-employee send button. `employeeIds` omitted/empty means every payslip in the run. */
  distributePayslips: (runId: string, payload: { employeeIds?: string[]; channels: ('PORTAL' | 'EMAIL')[] }, token: string) =>
    fetch(`${BASE}/runs/${runId}/payslips/distribute`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(handle<{ releasedCount: number; emailedCount: number; emailFailedCount: number }>),

  /** "Download All" — every payslip in the run, zipped, regardless of distribution state. */
  downloadAllPayslips: async (runId: string, token: string) => {
    const res = await fetch(`${BASE}/runs/${runId}/payslips/bulk-pdf`, { headers: bearerOnly(token) });
    if (!res.ok) throw new Error(`Failed to download payslips (${res.status})`);
    const disposition = res.headers.get('Content-Disposition') ?? '';
    const match = /filename="?([^";]+)"?/.exec(disposition);
    const fileName = match ? match[1] : 'payslips.zip';
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    a.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
  },

  /** "Export to Excel" — one Pay Register workbook, every employee in the run as a row. */
  downloadPayRegister: async (runId: string, token: string) => {
    const res = await fetch(`${BASE}/runs/${runId}/pay-register`, { headers: bearerOnly(token) });
    if (!res.ok) throw new Error(`Failed to download pay register (${res.status})`);
    const disposition = res.headers.get('Content-Disposition') ?? '';
    const match = /filename="?([^";]+)"?/.exec(disposition);
    const fileName = match ? match[1] : 'pay-register.xlsx';
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    a.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
  },

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

  bulkDecideNoAttendance: (runId: string, payload: { itemIds: string[]; action: 'DEDUCT_FROM_LEAVE' | 'MARK_LOP' | 'NO_ACTION'; selectedLeaveTypeId?: string; comment?: string }, token: string) =>
    fetch(`${BASE}/runs/${runId}/review/no-attendance/decide`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<PayrollRunReviewItemResponse[]>),

  // US-C24 — Ad-hoc payments (run-scoped)
  listAdHocPayments: (runId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/ad-hoc-payments`, { headers: bearerOnly(token) }).then(handle<AdHocPaymentResponse[]>),

  addAdHocPayment: (runId: string, payload: { employeeId: string; componentName: string; amount: number; reason: string }, token: string) =>
    fetch(`${BASE}/runs/${runId}/ad-hoc-payments`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<AdHocPaymentResponse>),

  bulkAddAdHocPayment: (runId: string, payload: { componentName: string; amount: number; reason: string }, token: string) =>
    fetch(`${BASE}/runs/${runId}/ad-hoc-payments/bulk`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<BulkAdHocPaymentResult>),

  removeAdHocPayment: (runId: string, paymentId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/ad-hoc-payments/${paymentId}`, { method: 'DELETE', headers: authHeaders(token) }).then(handle<void>),

  // US-C24 follow-up — recurring ad-hoc payment rules (Payroll Setup, not run-scoped)
  listRecurringAdHocPaymentRules: (token: string) =>
    fetch(`${BASE}/recurring-ad-hoc-payment-rules`, { headers: bearerOnly(token) }).then(handle<RecurringAdHocPaymentRuleResponse[]>),

  createRecurringAdHocPaymentRule: (payload: { payGroupId?: string | null; componentName: string; amount: number; reason: string }, token: string) =>
    fetch(`${BASE}/recurring-ad-hoc-payment-rules`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<RecurringAdHocPaymentRuleResponse>),

  setRecurringAdHocPaymentRuleActive: (id: string, active: boolean, token: string) =>
    fetch(`${BASE}/recurring-ad-hoc-payment-rules/${id}/active`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify({ active }) }).then(handle<RecurringAdHocPaymentRuleResponse>),

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

  // US-C16 — Cost report (only buildable once a run is APPROVED or DISBURSED — backend-enforced)
  getCostReport: (runId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/cost-report`, { headers: bearerOnly(token) }).then(handle<PayrollCostReportResponse>),

  // New Joinees & Exits
  getJoinersExits: (runId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/joiners-exits`, { headers: bearerOnly(token) }).then(handle<PayrollJoinersExitsResponse>),

  // Salary Component Claim / Expenses — read-only visibility into expense claims cleared for payroll
  getClaims: (runId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/claims`, { headers: bearerOnly(token) }).then(handle<PayrollClaimResponse[]>),

  // Statutory Override — one employee's PF/ESI/Professional Tax amount for one cycle
  createStatutoryOverride: (payload: { employeeId: string; payGroupId: string; referenceDate?: string; ruleType: string; overrideAmount: number; overrideReason: string }, token: string) =>
    fetch(`${BASE}/statutory-overrides`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<StatutoryOverrideResponse>),

  clearStatutoryOverride: (employeeId: string, ruleType: string, payGroupId: string, referenceDate: string | undefined, token: string) => {
    const params = new URLSearchParams({ employeeId, ruleType, payGroupId });
    if (referenceDate) params.set('referenceDate', referenceDate);
    return fetch(`${BASE}/statutory-overrides?${params.toString()}`, { method: 'DELETE', headers: authHeaders(token) }).then(handle<void>);
  },

  listStatutoryOverridesForEmployee: (employeeId: string, token: string) =>
    fetch(`${BASE}/statutory-overrides/by-employee/${employeeId}`, { headers: bearerOnly(token) }).then(handle<StatutoryOverrideResponse[]>),

  // What the StatutoryRule formula alone would compute right now, ignoring any existing override —
  // shown on the Override form as a "here's what you'd be overriding" reference.
  previewStatutoryAmount: (employeeId: string, ruleType: string, payGroupId: string, referenceDate: string | undefined, token: string) => {
    const params = new URLSearchParams({ employeeId, ruleType, payGroupId });
    if (referenceDate) params.set('referenceDate', referenceDate);
    return fetch(`${BASE}/statutory-overrides/preview?${params.toString()}`, { headers: bearerOnly(token) }).then(handle<StatutoryPreviewResponse>);
  },

  // Arrears — read-only, auto-computed (see Arrears' own backend doc comment)
  getArrears: (runId: string, token: string) =>
    fetch(`${BASE}/runs/${runId}/arrears`, { headers: bearerOnly(token) }).then(handle<ArrearsResponse[]>),

  // US-C19 — Pay Groups (full CRUD; previously only wrapped for reading)
  createPayGroup: (payload: { name: string; legalEntity?: string; address?: string; frequency?: string; currency?: string; cycleStartDay: number; cycleEndDay: number; processingDay: number }, token: string) =>
    fetch(`${BASE}/pay-groups`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<PayGroupResponse>),

  updatePayGroup: (id: string, payload: Partial<{ name: string; legalEntity: string; address: string; frequency: string; currency: string; cycleStartDay: number; cycleEndDay: number; processingDay: number; active: boolean }>, token: string) =>
    fetch(`${BASE}/pay-groups/${id}`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<PayGroupResponse>),

  // Keka restyle — Filing Details (§3.8): Income Tax/PF/ESI, one row per pay group.
  getFilingDetails: (payGroupId: string, token: string) =>
    fetch(`${BASE}/pay-groups/${payGroupId}/filing-details`, { headers: bearerOnly(token) }).then(handle<PayGroupFilingDetailsResponse>),

  updateFilingDetails: (payGroupId: string, payload: {
    pan?: string; tan?: string; tanCircleNumber?: string; citTdsLocation?: string; form130Signatory?: string;
    signatoryDesignation?: string; signatoryFatherName?: string; pfEstablishmentId?: string; esiEstablishmentId?: string;
  }, token: string) =>
    fetch(`${BASE}/pay-groups/${payGroupId}/filing-details`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<PayGroupFilingDetailsResponse>),

  // Keka restyle — "PT Registered states"/"Registered PT Locations" (§3.8).
  listPtLocations: (payGroupId: string, token: string) =>
    fetch(`${BASE}/pay-groups/${payGroupId}/pt-locations`, { headers: bearerOnly(token) }).then(handle<PtRegisteredLocationResponse[]>),

  createPtLocation: (payGroupId: string, payload: {
    stateName: string; locationName: string; establishmentId?: string; registrationDate?: string;
    signatory?: string; signatoryDesignation?: string; signatoryFatherName?: string;
  }, token: string) =>
    fetch(`${BASE}/pay-groups/${payGroupId}/pt-locations`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<PtRegisteredLocationResponse>),

  updatePtLocation: (payGroupId: string, id: string, payload: {
    stateName: string; locationName: string; establishmentId?: string; registrationDate?: string;
    signatory?: string; signatoryDesignation?: string; signatoryFatherName?: string;
  }, token: string) =>
    fetch(`${BASE}/pay-groups/${payGroupId}/pt-locations/${id}`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<PtRegisteredLocationResponse>),

  deletePtLocation: (payGroupId: string, id: string, token: string) =>
    fetch(`${BASE}/pay-groups/${payGroupId}/pt-locations/${id}`, { method: 'DELETE', headers: authHeaders(token) }).then(res => {
      if (!res.ok) throw new Error(`Request failed (${res.status})`);
    }),

  // Keka restyle — Configure Payslip Settings (§3.9). Genuinely read by the PDF renderer
  // (PayslipService#toModel) — a pay group with no row renders exactly as it always has.
  getPayslipSettings: (payGroupId: string, token: string) =>
    fetch(`${BASE}/pay-groups/${payGroupId}/payslip-settings`, { headers: bearerOnly(token) }).then(handle<PayslipSettingsResponse>),

  updatePayslipSettings: (payGroupId: string, payload: {
    passwordProtected: boolean; documentHeaderText?: string; footerText?: string;
    logoAlignment?: 'LEFT' | 'RIGHT'; excludeNaFields?: boolean;
    earningsHeading?: string; deductionsHeading?: string;
    includeLeaveSummary?: boolean; includeArrearBreakup?: boolean;
    includeYtdColumn?: boolean; includeEmployerContributions?: boolean;
    payslipFields?: PayslipFieldKey[];
  }, token: string) =>
    fetch(`${BASE}/pay-groups/${payGroupId}/payslip-settings`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<PayslipSettingsResponse>),

  payslipLogoUrl: (payGroupId: string) => `${BASE}/pay-groups/${payGroupId}/payslip-settings/logo`,

  uploadPayslipLogo: (payGroupId: string, file: File, token: string) => {
    const form = new FormData();
    form.append('file', file);
    return fetch(`${BASE}/pay-groups/${payGroupId}/payslip-settings/logo`, {
      method: 'POST', headers: bearerOnly(token), body: form,
    }).then(handle<PayslipSettingsResponse>);
  },

  removePayslipLogo: (payGroupId: string, token: string) =>
    fetch(`${BASE}/pay-groups/${payGroupId}/payslip-settings/logo`, { method: 'DELETE', headers: authHeaders(token) })
      .then(handle<PayslipSettingsResponse>),

  // Keka restyle — Import Financial Information (§3.4). The import wizard calls the per-employee
  // form once per valid row, same loop pattern as the salary import.
  getEmployeeFinancialDetails: (employeeId: string, token: string) =>
    fetch(`${BASE}/employees/${employeeId}/financial-details`, { headers: bearerOnly(token) }).then(handle<EmployeeFinancialDetailsResponse>),

  updateEmployeeFinancialDetails: (employeeId: string, payload: Partial<{
    bankAccountNumber: string; bankName: string; bankIfsc: string; panNumber: string; uanNumber: string; esiNumber: string;
  }>, token: string) =>
    fetch(`${BASE}/employees/${employeeId}/financial-details`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<EmployeeFinancialDetailsResponse>),

  // Keka restyle — "Financial Information" report (§3.6): every employee in one table.
  listAllFinancialDetails: (token: string) =>
    fetch(`${BASE}/employees/financial-details`, { headers: bearerOnly(token) }).then(handle<EmployeeFinancialDetailsResponse[]>),

  // US-C20 — Salary Component catalog (full CRUD; previously only wrapped for reading)
  createSalaryComponent: (payload: { name: string; type: string; taxable?: boolean; taxSection?: string; includedInCtc?: boolean; shownOnPayslip?: boolean; prorateOnLop?: boolean }, token: string) =>
    fetch(`${BASE}/salary-components`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<SalaryComponentCatalogResponse>),

  updateSalaryComponent: (id: string, payload: Partial<{ name: string; type: string; taxable: boolean; taxSection: string; includedInCtc: boolean; shownOnPayslip: boolean; prorateOnLop: boolean; active: boolean; accountingCode: string }>, token: string) =>
    fetch(`${BASE}/salary-components/${id}`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<SalaryComponentCatalogResponse>),

  // Keka's "Order of components" screen (§3.8) — the full catalog's ids in the desired order;
  // the backend assigns displayOrder = index in one transaction.
  reorderSalaryComponents: (orderedIds: string[], token: string) =>
    fetch(`${BASE}/salary-components/order`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify({ orderedIds }) }).then(handle<SalaryComponentCatalogResponse[]>),

  // US-C13 — Statutory Rules (config/versioning only — see StatutoryRuleResponse's own doc comment)
  listStatutoryRules: (token: string) =>
    fetch(`${BASE}/statutory-rules`, { headers: bearerOnly(token) }).then(handle<StatutoryRuleResponse[]>),

  getStatutoryRuleHistory: (country: string, ruleType: string, token: string) =>
    fetch(`${BASE}/statutory-rules/history?${new URLSearchParams({ country, ruleType }).toString()}`, { headers: bearerOnly(token) }).then(handle<StatutoryRuleResponse[]>),

  createOrReviseStatutoryRule: (payload: { country: string; ruleType: string; slabConfig: string; effectiveFrom: string }, token: string) =>
    fetch(`${BASE}/statutory-rules`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<StatutoryRuleResponse>),

  // Safety check: a Location with employees whose Country doesn't match any configured rule.
  getLocationCountryMismatches: (token: string) =>
    fetch(`${BASE}/statutory-rules/location-mismatches`, { headers: bearerOnly(token) }).then(handle<LocationCountryMismatchResponse[]>),
};
