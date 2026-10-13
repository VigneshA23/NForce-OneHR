import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/account-owner/fences`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error((body as any).message ?? `Request failed (${res.status})`);
  return body as T;
}

export interface ModuleFence {
  id: number;
  module: string;
  moduleDisplayName: string;
  action: string;
  fencedAt: string;
  fencedBy: string;
}

export const accountOwnerApi = {
  listFences: (token: string) =>
    fetch(BASE, { headers: authHeaders(token) }).then(handle<ModuleFence[]>),

  fence: (moduleCode: string, action: string, token: string) =>
    fetch(BASE, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ module: moduleCode, action }) }).then(handle<ModuleFence>),

  unfence: (moduleCode: string, action: string, token: string) =>
    fetch(`${BASE}/${moduleCode}/${action}`, { method: 'DELETE', headers: authHeaders(token) }).then(async (res) => {
      if (!res.ok) {
        let body: { message?: string } = {};
        try { body = await res.json(); } catch { /* non-json */ }
        throw new Error((body as any).message ?? `Request failed (${res.status})`);
      }
    }),
};
