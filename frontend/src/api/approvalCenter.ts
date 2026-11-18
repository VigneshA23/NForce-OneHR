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

// WEB_CLOCK_IN is deliberately absent — Web Clock-In needs no approval at all anymore (see
// WebClockInService's own class Javadoc) and can never appear in this queue.
export type RequestType =
  | 'LEAVE' | 'REGULARIZATION' | 'EXPENSE' | 'ASSET_REQUEST'
  | 'WFH' | 'PARTIAL_DAY' | 'OVERTIME' | 'HELP_CONTENT' | 'PROFILE_CHANGE';

/** One changed field of an employee's bank / government-ID edit. Identifier-like values arrive masked
 *  (XXXX-1234); the full values come from the audited detail call in profileChangeRequests.ts. */
export interface ProfileChangeField {
  field: string;
  label: string;
  oldValue: string | null;
  newValue: string | null;
}

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

  // Help Content (FAQ/Guide) — `id` above is the *approval attempt* id (what Approve/Reject
  // act on via helpContentApprovalApi), not the content id.
  helpContentId?: string;
  helpContentType?: 'FAQ' | 'QUICK_HELP' | 'GUIDE' | 'DOCUMENT';
  helpContentTitle?: string;
  helpContentDescription?: string;
  helpContentBody?: string;
  helpContentCategory?: string;
  helpContentAttemptNumber?: number;
  helpContentModifiedSincePrevious?: boolean;

  // Profile change (employee bank / government-ID edit) — `id` is the change-request id.
  profileChangeFields?: ProfileChangeField[];
}

export const approvalCenterApi = {
  listPending: (token: string) =>
    fetch(`${BASE}`, { headers: authHeaders(token) }).then(handle<ApprovalItem[]>),
};
