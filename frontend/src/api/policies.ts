const BASE_POLICIES = '/api/policies';
const BASE_ANNOUN = '/api/announcements';

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error((body as any).message ?? `Request failed (${res.status})`);
  return body as T;
}

// ── Types ──────────────────────────────────────────────────

export interface Policy {
  id: number;
  title: string;
  version: string;
  description: string;
  audience: string;
  required: boolean;
  publishedAt: string;
  publishedBy: string | null;
  active: boolean;
  acknowledged: boolean | null;
  acknowledgedAt: string | null;
}

export interface PolicyAcknowledgment {
  id: number;
  policyId: number;
  policyTitle: string;
  policyVersion: string;
  employeeUserId: string;
  employeeName: string | null;
  acknowledgedAt: string | null;
  pending: boolean;
}

export interface Announcement {
  id: number;
  title: string;
  body: string;
  audience: string;
  scheduledFor: string | null;
  publishedAt: string | null;
  createdBy: string | null;
  createdAt: string;
  published: boolean;
  active: boolean;
}

// ── Employee ───────────────────────────────────────────────

export async function myPolicies(token: string): Promise<Policy[]> {
  return handle(await fetch(`${BASE_POLICIES}/my`, { headers: authHeaders(token) }));
}

export async function pendingPolicyCount(token: string): Promise<number> {
  return handle(await fetch(`${BASE_POLICIES}/my/pending-count`, { headers: authHeaders(token) }));
}

export async function acknowledgePolicy(token: string, policyId: number): Promise<void> {
  const res = await fetch(`${BASE_POLICIES}/${policyId}/acknowledge`, { method: 'POST', headers: authHeaders(token) });
  if (!res.ok) {
    let body: { message?: string } = {};
    try { body = await res.json(); } catch { /* */ }
    throw new Error((body as any).message ?? `Failed (${res.status})`);
  }
}

export async function publishedAnnouncements(token: string): Promise<Announcement[]> {
  return handle(await fetch(`${BASE_ANNOUN}/published`, { headers: authHeaders(token) }));
}

// ── HR/SA ──────────────────────────────────────────────────

export async function listAllPolicies(token: string): Promise<Policy[]> {
  return handle(await fetch(BASE_POLICIES, { headers: authHeaders(token) }));
}

export async function publishPolicy(token: string, body: {
  title: string; version: string; description: string; audience?: string; required?: boolean;
}): Promise<Policy> {
  return handle(await fetch(BASE_POLICIES, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(body) }));
}

export async function editPolicy(token: string, id: number, body: {
  title?: string; description?: string; audience?: string;
}): Promise<Policy> {
  return handle(await fetch(`${BASE_POLICIES}/${id}`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify(body) }));
}

export async function deactivatePolicy(token: string, id: number): Promise<Policy> {
  return handle(await fetch(`${BASE_POLICIES}/${id}/deactivate`, { method: 'POST', headers: authHeaders(token) }));
}

export async function reactivatePolicy(token: string, id: number): Promise<Policy> {
  return handle(await fetch(`${BASE_POLICIES}/${id}/reactivate`, { method: 'POST', headers: authHeaders(token) }));
}

export async function deletePolicy(token: string, id: number): Promise<void> {
  const res = await fetch(`${BASE_POLICIES}/${id}`, { method: 'DELETE', headers: authHeaders(token) });
  if (!res.ok) throw new Error(`Delete failed (${res.status})`);
}

export async function listAcknowledgments(token: string, policyId: number): Promise<PolicyAcknowledgment[]> {
  return handle(await fetch(`${BASE_POLICIES}/${policyId}/acknowledgments`, { headers: authHeaders(token) }));
}

export async function listAllAnnouncements(token: string): Promise<Announcement[]> {
  return handle(await fetch(BASE_ANNOUN, { headers: authHeaders(token) }));
}

export async function createAnnouncement(token: string, body: {
  title: string; body: string; audience?: string; scheduledFor?: string | null; publishNow?: boolean;
}): Promise<Announcement> {
  return handle(await fetch(BASE_ANNOUN, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(body) }));
}

export async function publishAnnouncement(token: string, id: number): Promise<Announcement> {
  return handle(await fetch(`${BASE_ANNOUN}/${id}/publish`, { method: 'POST', headers: authHeaders(token) }));
}

export async function updateAnnouncement(token: string, id: number, body: {
  title?: string; body?: string; audience?: string;
}): Promise<Announcement> {
  return handle(await fetch(`${BASE_ANNOUN}/${id}`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify(body) }));
}

export async function deactivateAnnouncement(token: string, id: number): Promise<Announcement> {
  return handle(await fetch(`${BASE_ANNOUN}/${id}/deactivate`, { method: 'POST', headers: authHeaders(token) }));
}

export async function reactivateAnnouncement(token: string, id: number): Promise<Announcement> {
  return handle(await fetch(`${BASE_ANNOUN}/${id}/reactivate`, { method: 'POST', headers: authHeaders(token) }));
}

export async function deleteAnnouncement(token: string, id: number): Promise<void> {
  const res = await fetch(`${BASE_ANNOUN}/${id}`, { method: 'DELETE', headers: authHeaders(token) });
  if (!res.ok) throw new Error(`Delete failed (${res.status})`);
}

export async function resetAcknowledgment(token: string, policyId: number, userId: string): Promise<void> {
  const res = await fetch(`/api/policies/${policyId}/acknowledgments/${userId}`, { method: 'DELETE', headers: authHeaders(token) });
  if (!res.ok) throw new Error(`Reset failed (${res.status})`);
}

export async function remindEmployee(token: string, policyId: number, userId: string): Promise<void> {
  const res = await fetch(`/api/policies/${policyId}/remind/${userId}`, { method: 'POST', headers: authHeaders(token) });
  if (!res.ok) throw new Error(`Remind failed (${res.status})`);
}

export async function globalPendingAckCount(token: string): Promise<number> {
  return handle(await fetch('/api/policies/pending-ack-count', { headers: authHeaders(token) }));
}
