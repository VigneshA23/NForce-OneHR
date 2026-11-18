import { API_ORIGIN } from './config';
import type { ProfileChangeField } from './approvalCenter';

const BASE = `${API_ORIGIN}/api/profile/change-requests`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error(body.message ?? `Request failed (${res.status})`);
  return body as T;
}

export interface ProfileChangeRequest {
  id: string;
  employeeUserId: string;
  employeeName: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  createdAt: string;
  reviewedAt?: string;
  reviewComment?: string;
  /** True only for the audited detail call, which returns the raw values. */
  unmasked: boolean;
  fields: ProfileChangeField[];
}

// Approve / reject are issued from the Approval Center (see approveItem / rejectItem there).
export const profileChangeRequestApi = {
  /** Raw values for the approver to verify. Recorded in the audit log as an unmasked view. */
  detail: (id: string, token: string) =>
    fetch(`${BASE}/${id}`, { headers: authHeaders(token) }).then(handle<ProfileChangeRequest>),
  approve: (id: string, token: string) =>
    fetch(`${BASE}/${id}/approve`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify({}) })
      .then(handle<ProfileChangeRequest>),
  reject: (id: string, reason: string, token: string) =>
    fetch(`${BASE}/${id}/reject`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify({ comment: reason }) })
      .then(handle<ProfileChangeRequest>),
  mine: (token: string) =>
    fetch(`${BASE}/mine`, { headers: authHeaders(token) }).then(handle<ProfileChangeRequest[]>),
};
