import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/kudos`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error(body.message ?? `Request failed (${res.status})`);
  return body as T;
}

export interface KudosRecord {
  id: number;
  fromUserId: string;
  fromName: string;
  toUserId: string;
  toName: string;
  category: string;
  note: string | null;
  createdAt: string;
}

export interface SendKudosPayload {
  toUserId: string;
  category: string;
  note?: string;
}

/** "Appreciate your lead" / peer kudos (ONEHR-73) — scoped server-side to the caller's
 * reporting manager and current peers; sending to anyone else is rejected. */
export const kudosApi = {
  send: (payload: SendKudosPayload, token: string) =>
    fetch(BASE, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(handle<KudosRecord>),

  received: (token: string) =>
    fetch(`${BASE}/received`, { headers: authHeaders(token) }).then(handle<KudosRecord[]>),

  sent: (token: string) =>
    fetch(`${BASE}/sent`, { headers: authHeaders(token) }).then(handle<KudosRecord[]>),
};
