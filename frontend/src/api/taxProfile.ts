import { API_ORIGIN } from './config';

export interface RegimeView { regime: 'OLD' | 'NEW'; isDefault: boolean; windowOpen: boolean; opensOn: string | null; closesOn: string | null }

async function call<T>(path: string, token: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_ORIGIN}${path}`, { ...init, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } });
  if (!res.ok) {
    let msg = `Request failed (${res.status})`;
    try { msg = (await res.json()).message ?? msg; } catch { /* non-json */ }
    throw new Error(msg);
  }
  return res.json() as Promise<T>;
}

/** US-F04 / US-F06 — own data only; the server takes the user from the token. */
export const taxProfileApi = {
  esiWageCeiling: (token: string) => call<{ wageCeiling: number | null }>('/api/me/finances/esi-ceiling', token).then(r => r.wageCeiling),
  regime: (token: string) => call<RegimeView>('/api/me/finances/tax-regime', token),
  setRegime: (regime: 'OLD' | 'NEW', token: string) =>
    call<RegimeView>('/api/me/finances/tax-regime', token, { method: 'PUT', body: JSON.stringify({ regime }) }),
};
