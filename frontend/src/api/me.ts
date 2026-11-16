import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api`;

/** Mirrors backend's MyPermissionsResponse — module -> action -> the scopes actually granted
 *  (NOT cascaded: an ALL grant lists only "ALL" — see hasPermission's SCOPE_COVERAGE). */
export interface MyPermissionsResponse {
  primaryRole: string;
  roles: string[];
  permissions: Record<string, Partial<Record<string, string[]>>>;
  /** The subset granted by custom (non-system) roles — drives the "custom roles" view in the Roles & Permissions UI. Optional: a
   *  persisted response from before this field existed simply has none. */
  customRolePermissions?: Record<string, Partial<Record<string, string[]>>>;
}

/** One page that needs a permission: open when the user holds AT LEAST ONE of `requires` ("module:ACTION:SCOPE").
 *  Served from the backend's permission-catalogue.yml; every page not listed is baseline self-service. */
export interface RouteRequirement {
  path: string;
  requires: string[];
}

export interface AccessCatalogue {
  routes: RouteRequirement[];
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
  getAccessCatalogue: (token: string) =>
    fetch(`${BASE}/me/access-catalogue`, {
      headers: { Authorization: `Bearer ${token}` },
    }).then(handle<AccessCatalogue>),
};
