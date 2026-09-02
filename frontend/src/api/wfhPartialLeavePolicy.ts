import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/settings/wfh-partial-leave-policy`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error(body.message ?? `Request failed (${res.status})`);
  return body as T;
}

/** Org-wide WFH/Partial Day monthly limits — Super Admin only. Read fresh from the DB on the
 * backend (never cached), so a save here is reflected for every employee's very next
 * request/balance check with no redeploy — see WfhPartialLeavePolicyService. */
export interface WfhPartialLeavePolicy {
  wfhMonthlyLimitDays: number;
  partialLeaveMonthlyLimitMinutes: number;
  updatedByName: string | null;
  updatedAt: string | null;
}

export interface UpdateWfhPartialLeavePolicyPayload {
  wfhMonthlyLimitDays: number;
  partialLeaveMonthlyLimitMinutes: number;
}

export const wfhPartialLeavePolicyApi = {
  get: (token: string) =>
    fetch(BASE, { headers: authHeaders(token) }).then(r => handle<WfhPartialLeavePolicy>(r)),

  update: (payload: UpdateWfhPartialLeavePolicyPayload, token: string) =>
    fetch(BASE, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(r => handle<WfhPartialLeavePolicy>(r)),
};
