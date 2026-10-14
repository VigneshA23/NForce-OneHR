import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api`;

/** Mirrors backend's MyPermissionsResponse — module -> action -> every scope actually granted
 *  (already cascaded server-side, e.g. an ALL grant also lists OWN/TEAM/DEPARTMENT/LOCATION). */
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
