import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api`;

/** Mirrors backend's MyPermissionsResponse — module -> action -> the scopes actually granted
 *  (NOT cascaded: an ALL grant lists only "ALL" — see hasPermission's SCOPE_COVERAGE). */
export interface MyPermissionsResponse {
  primaryRole: string;
  roles: string[];
  permissions: Record<string, Partial<Record<string, string[]>>>;
}

async function handle<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error('Request failed');
  return res.json() as Promise<T>;
}

export const meApi = {
  getPermissions: (token: string) =>
    fetch(`${BASE}/me/permissions`, {
      headers: { Authorization: `Bearer ${token}` },
    }).then(handle<MyPermissionsResponse>),
};
