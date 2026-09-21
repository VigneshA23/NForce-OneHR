import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/expenses`;

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

/** Same fetch-then-blob pattern as helpContentApprovalApi.downloadAttachment — bytes only ever
 *  reach the DOM via an authenticated blob fetch, never a raw data: URI baked into page HTML, so
 *  the backend's own role check (ExpenseService#getReceipt) is what actually gates access, not
 *  which page happens to render a button. On failure, surfaces the backend's own message (e.g.
 *  "No receipt attached to this claim") rather than a generic one. */
async function handleBlob(res: Response): Promise<Blob> {
  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    try { const body = await res.json(); if (body?.message) message = body.message; } catch { /* non-json */ }
    throw new Error(message);
  }
  return res.blob();
}

export interface ExpenseCategory {
  id: number;
  name: string;
  requiresReceiptAbove: number;
  dailyLimit: number | null;
  secondApprovalAbove: number | null;
}

export interface ExpenseClaimResponse {
  id: string;
  employeeUserId: string;
  employeeName: string;
  categoryId: number;
  categoryName: string;
  amount: number;
  expenseDate: string;
  businessPurpose: string;
  receiptUrl: string | null;
  status: string;
  managerDecidedByName: string | null;
  managerDecidedAt: string | null;
  managerRejectionReason: string | null;
  finalDecidedByName: string | null;
  finalDecidedAt: string | null;
  finalRejectionReason: string | null;
  paidAt: string | null;
  createdAt: string;
  // Workflow Studio: false means Manager approval alone cleared this claim straight to
  // CLEARED_FOR_PAYROLL — there was never an HR/final stage to display for it.
  requiresSecondApproval: boolean;
}

export interface ExpenseTileEmployee {
  openClaimCount: number;
  openClaimAmount: number;
  approvedThisMonthAmount: number;
  approvedThisMonthCount: number;
}

export interface ExpenseTileManager {
  pendingCount: number;
  pendingAmount: number;
  approvedThisMonthAmount: number;
  approvedThisMonthCount: number;
}

export interface ExpenseTileHR {
  pendingClearanceCount: number;
  pendingAmount: number;
}

export const expensesApi = {
  categories: (token?: string) =>
    fetch(`${BASE}/categories`, token ? { headers: authHeaders(token) } : {}).then(handle<ExpenseCategory[]>),

  createCategory: (payload: {
    name: string; requiresReceiptAbove: number;
    dailyLimit?: number | null; secondApprovalAbove?: number | null;
  }, token: string) =>
    fetch(`${BASE}/categories`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<ExpenseCategory>),

  updateCategory: (id: number, payload: {
    name: string; requiresReceiptAbove: number;
    dailyLimit?: number | null; secondApprovalAbove?: number | null;
  }, token: string) =>
    fetch(`${BASE}/categories/${id}`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<ExpenseCategory>),

  submit: (payload: {
    categoryId: number; amount: number; expenseDate: string;
    businessPurpose: string; receiptUrl?: string | null;
  }, token: string) =>
    fetch(`${BASE}/claims`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<ExpenseClaimResponse>),

  myClaims: (token: string) =>
    fetch(`${BASE}/claims/mine`, { headers: authHeaders(token) }).then(handle<ExpenseClaimResponse[]>),

  getReceipt: (claimId: string, token: string) =>
    fetch(`${BASE}/claims/${claimId}/receipt`, { headers: bearerOnly(token) }).then(handleBlob),

  pendingForManager: (token: string) =>
    fetch(`${BASE}/claims/pending-manager`, { headers: authHeaders(token) }).then(handle<ExpenseClaimResponse[]>),

  allTeamClaims: (token: string) =>
    fetch(`${BASE}/claims/team-all`, { headers: authHeaders(token) }).then(handle<ExpenseClaimResponse[]>),

  managerApprove: (id: string, token: string) =>
    fetch(`${BASE}/claims/${id}/manager-approve`, { method: 'POST', headers: authHeaders(token) }).then(handle<ExpenseClaimResponse>),

  managerReject: (id: string, reason: string, token: string) =>
    fetch(`${BASE}/claims/${id}/manager-reject`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ reason }) }).then(handle<ExpenseClaimResponse>),

  pendingForFinal: (token: string) =>
    fetch(`${BASE}/claims/pending-final`, { headers: authHeaders(token) }).then(handle<ExpenseClaimResponse[]>),

  finalApprove: (id: string, token: string) =>
    fetch(`${BASE}/claims/${id}/final-approve`, { method: 'POST', headers: authHeaders(token) }).then(handle<ExpenseClaimResponse>),

  finalReject: (id: string, reason: string, token: string) =>
    fetch(`${BASE}/claims/${id}/final-reject`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ reason }) }).then(handle<ExpenseClaimResponse>),

  clearedForPayroll: (token: string) =>
    fetch(`${BASE}/claims/cleared-for-payroll`, { headers: authHeaders(token) }).then(handle<ExpenseClaimResponse[]>),

  markPaid: (id: string, token: string) =>
    fetch(`${BASE}/claims/${id}/mark-paid`, { method: 'POST', headers: authHeaders(token) }).then(handle<ExpenseClaimResponse>),

  employeeTiles: (token: string) =>
    fetch(`${BASE}/tiles/employee`, { headers: authHeaders(token) }).then(handle<ExpenseTileEmployee>),

  managerTiles: (token: string) =>
    fetch(`${BASE}/tiles/manager`, { headers: authHeaders(token) }).then(handle<ExpenseTileManager>),

  hrTiles: (token: string) =>
    fetch(`${BASE}/tiles/hr`, { headers: authHeaders(token) }).then(handle<ExpenseTileHR>),
};
