import { API_ORIGIN } from './config';
import type { Attachment, HelpContentDetail, HelpContentType } from './helpContent';

const BASE = `${API_ORIGIN}/api/help-content/approvals`;

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

export interface ApprovalAttempt {
  id: string;
  contentId: string;
  contentType: HelpContentType | null;
  contentTitle: string;
  attemptNumber: number;
  submittedByUserId: string;
  submittedByName: string;
  submittedAt: string;
  approverName: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN';
  decidedAt: string | null;
  rejectionReason: string | null;
  withdrawalReason: string | null;
  snapshotTitle: string;
  snapshotDescription: string | null;
  snapshotBody: string | null;
  snapshotCategory: string | null;
  snapshotFeatured: boolean;
  snapshotDisplayOrder: number;
  attachments: Attachment[];
  modifiedSincePrevious: boolean;
}

export interface DiffSegment {
  type: 'EQUAL' | 'ADDED' | 'REMOVED';
  text: string;
}

export interface FieldChange {
  fieldName: string;
  changed: boolean;
  oldValue: string | null;
  newValue: string | null;
  segments: DiffSegment[];
}

export interface AttachmentChange {
  changeType: 'ADDED' | 'REMOVED' | 'REPLACED' | 'REORDERED' | 'UNCHANGED';
  fileName: string | null;
  previousFileName: string | null;
  displayOrder: number | null;
  previousDisplayOrder: number | null;
}

export interface ApprovalDiff {
  previous: ApprovalAttempt | null;
  current: ApprovalAttempt;
  modified: boolean;
  fieldChanges: FieldChange[];
  attachmentChanges: AttachmentChange[];
}

export const helpContentApprovalApi = {
  getAttempt: (attemptId: string, token: string) =>
    fetch(`${BASE}/${attemptId}`, { headers: authHeaders(token) }).then(handle<ApprovalAttempt>),

  getDiff: (attemptId: string, token: string) =>
    fetch(`${BASE}/${attemptId}/diff`, { headers: authHeaders(token) }).then(handle<ApprovalDiff>),

  downloadAttachment: async (attemptId: string, attachmentId: string, token: string) => {
    const res = await fetch(`${BASE}/${attemptId}/attachments/${attachmentId}`, { headers: bearerOnly(token) });
    if (!res.ok) throw new Error(`Download failed (${res.status})`);
    return res.blob();
  },

  approve: (attemptId: string, token: string) =>
    fetch(`${BASE}/${attemptId}/approve`, { method: 'POST', headers: authHeaders(token) }).then(handle<HelpContentDetail>),

  reject: (attemptId: string, reason: string, token: string) =>
    fetch(`${BASE}/${attemptId}/reject`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ reason }) }).then(handle<HelpContentDetail>),
};
