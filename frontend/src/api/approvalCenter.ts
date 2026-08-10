import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/approvals`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error((body as any).message ?? `Request failed (${res.status})`);
  return body as T;
}

export type RequestType =
  | 'LEAVE' | 'REGULARIZATION' | 'WEB_CLOCK_IN' | 'EXPENSE' | 'ASSET_REQUEST'
  | 'WFH' | 'PARTIAL_DAY' | 'OVERTIME';

export interface ApprovalItem {
  id: string;
  requestType: RequestType;
  employeeUserId: string;
  employeeName: string;
  createdAt: string;

  // Leave
  leaveTypeName?: string;
  leaveStartDate?: string;
  leaveEndDate?: string;
  leaveTotalDays?: number;
  leaveHalfDay?: boolean;
  leaveReason?: string;

  // Regularization / WFH / Partial Day / Overtime (shared — see backend ApprovalItemDto)
  attendanceDate?: string;
  requestedCheckIn?: string;
  requestedCheckOut?: string;
  regularizationReason?: string;
  /** PARTIAL_DAY only. */
  partialDayHours?: number;

  // Expense
  expenseCategoryName?: string;
  expenseAmount?: number;
  expenseDate?: string;
  businessPurpose?: string;
  receiptUrl?: string;
  approvalStage?: 'MANAGER' | 'FINAL';

  // Asset Request
  requestedCategoryName?: string;
  assetRequestReason?: string;
  assetRequestStatus?: string; // 'PENDING' | 'APPROVED'
}

export const approvalCenterApi = {
  listPending: (token: string) =>
    fetch(`${BASE}`, { headers: authHeaders(token) }).then(handle<ApprovalItem[]>),
};
