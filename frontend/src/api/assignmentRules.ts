import { API_ORIGIN } from './config';

const BASE = `${API_ORIGIN}/api/assignment-rules`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* empty body (DELETE) */ }
  if (!res.ok) throw new Error(body.message ?? `Request failed (${res.status})`);
  return body as T;
}

/** The shared rule engine serves every policy domain; only PENALIZATION is wired up so far. */
export type AssignmentDomain = 'PENALIZATION' | 'LEAVE_PLAN' | 'WFH_POLICY';

export interface AssignmentRule {
  id: string;
  domain: AssignmentDomain;
  targetId: string;
  targetName: string | null;
  name: string;
  /** Lowest number is evaluated first. */
  priority: number;
  departmentId: string | null;
  departmentName: string | null;
  locationId: string | null;
  locationName: string | null;
  shiftId: string | null;
  shiftName: string | null;
  employmentType: string | null;
  grade: string | null;
  active: boolean;
  /** e.g. "Department = Engineering AND Location = Bangalore" */
  criteriaText: string;
  /** Employees currently assigned by this rule. */
  assignedCount: number;
  updatedAt: string;
}

export interface AssignmentRuleInput {
  domain: AssignmentDomain;
  targetId: string;
  name: string;
  priority?: number | null;
  departmentId?: string | null;
  locationId?: string | null;
  shiftId?: string | null;
  employmentType?: string | null;
  grade?: string | null;
  active?: boolean;
}

export const assignmentRulesApi = {
  list: (domain: AssignmentDomain, token: string) =>
    fetch(`${BASE}?domain=${domain}`, { headers: authHeaders(token) }).then(handle<AssignmentRule[]>),
  create: (input: AssignmentRuleInput, token: string) =>
    fetch(BASE, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(input) }).then(handle<AssignmentRule>),
  update: (id: string, input: AssignmentRuleInput, token: string) =>
    fetch(`${BASE}/${id}`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(input) }).then(handle<AssignmentRule>),
  remove: (id: string, token: string) =>
    fetch(`${BASE}/${id}`, { method: 'DELETE', headers: authHeaders(token) }).then(r => handle<void>(r)),
  reorder: (domain: AssignmentDomain, orderedRuleIds: string[], token: string) =>
    fetch(`${BASE}/reorder`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ domain, orderedRuleIds }) }).then(handle<AssignmentRule[]>),
  /** Repairs drift: re-evaluates every employee and reports how many recorded outcomes changed. */
  reevaluate: (domain: AssignmentDomain, token: string) =>
    fetch(`${BASE}/reevaluate?domain=${domain}`, { method: 'POST', headers: authHeaders(token) }).then(handle<{ domain: string; changed: number }>),
};
