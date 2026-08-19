import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/org/penalisation-policies`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error(body.message ?? `Request failed (${res.status})`);
  return body as T;
}

/** Section 5: Policy List — the named/assignable PenalisationPolicy records themselves, distinct
 * from the rule configuration a policy holds (see api/penalizationPolicy.ts). */
export interface PenalisationPolicySummary {
  id: string;
  name: string;
  description: string | null;
  status: 'ACTIVE' | 'INACTIVE';
  employeeCount: number;
  currentVersion: number | null;
  effectiveFrom: string | null;
  createdBy: string;
  createdAt: string;
}

export const penalisationPoliciesApi = {
  list: (token: string): Promise<PenalisationPolicySummary[]> =>
    fetch(BASE, { headers: authHeaders(token) }).then(handle<PenalisationPolicySummary[]>),

  create: (token: string, name: string, description: string): Promise<PenalisationPolicySummary> =>
    fetch(BASE, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ name, description }) }).then(handle<PenalisationPolicySummary>),

  rename: (token: string, id: string, name: string, description: string): Promise<PenalisationPolicySummary> =>
    fetch(`${BASE}/${id}`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify({ name, description }) }).then(handle<PenalisationPolicySummary>),

  clone: (token: string, id: string, name: string, description: string): Promise<PenalisationPolicySummary> =>
    fetch(`${BASE}/${id}/clone`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ name, description }) }).then(handle<PenalisationPolicySummary>),

  remove: async (token: string, id: string): Promise<void> => {
    const res = await fetch(`${BASE}/${id}`, { method: 'DELETE', headers: authHeaders(token) });
    if (!res.ok) {
      let body: { message?: string } = {};
      try { body = await res.json(); } catch { /* non-json */ }
      throw new Error(body.message ?? `Request failed (${res.status})`);
    }
  },
};
