import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: unknown = null;
  try { body = await res.json(); } catch { /* empty / non-json */ }
  if (!res.ok) throw new Error((body as { message?: string } | null)?.message ?? `Request failed (${res.status})`);
  return body as T;
}

/** Mirrors backend RoleDtos — see RoleController. */
export interface Grant { module: string; action: string; scope: string; }

export interface RoleSummary {
  id: number;
  code: string;
  name: string;
  description: string | null;
  system: boolean;
  active: boolean;
  /** False for Super Admin — its permissions are locked. */
  permissionsEditable: boolean;
  /** False for every system role — name/description/status/delete are fixed. */
  metadataEditable: boolean;
  userCount: number;
  permissionCount: number;
  createdAt: string;
  updatedAt: string;
  /** Optimistic-lock version — sent back on every edit; a stale one gets a 409 "changed by someone else". */
  version: number;
}

export interface RoleMember { userId: string; fullName: string; email: string; employeeCode: string | null; active: boolean; }

export interface RoleDetail { role: RoleSummary; grants: Grant[]; members: RoleMember[]; }

/** `configurable: false` = still governed by role-code logic on the server; shown read-only. */
export interface CatalogueModule { code: string; name: string; configurable: boolean; actions: string[]; }

export interface RoleRef { id: number; code: string; name: string; system: boolean; active: boolean; /** @deprecated MANAGER is no longer granted automatically; always false. */ autoGranted?: boolean; }

/** One audited change to a role; `added`/`removed` are "module:ACTION:SCOPE" strings (permission edits only). */
export interface RoleHistoryEntry { id: number; occurredAt: string; action: string; actorName: string | null; summary: string; added: string[]; removed: string[]; }

/** A user who holds a permission through an active role, at the widest scope any of those roles grants. */
export interface PermissionHolder { userId: string; fullName: string; email: string; active: boolean; scope: string; roles: string[]; }

/** Per-user outcome of a bulk membership change. */
export interface BulkMemberResult { userId: string; name: string; ok: boolean; message: string; }

export interface EffectiveGrant { module: string; moduleName: string; action: string; scope: string; sources: string[]; }

export interface UserRoles { userId: string; fullName: string; email: string; roles: RoleRef[]; effective: EffectiveGrant[]; }

export const rolesApi = {
  list: (token: string) =>
    fetch(`${BASE}/roles`, { headers: authHeaders(token) }).then(handle<RoleSummary[]>),

  get: (id: number, token: string) =>
    fetch(`${BASE}/roles/${id}`, { headers: authHeaders(token) }).then(handle<RoleDetail>),

  catalogue: (token: string) =>
    fetch(`${BASE}/permissions/catalogue`, { headers: authHeaders(token) }).then(handle<CatalogueModule[]>),

  create: (payload: { name: string; description?: string; grants: Grant[] }, token: string) =>
    fetch(`${BASE}/roles`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<RoleDetail>),

  update: (id: number, payload: { name: string; description?: string; version: number }, token: string) =>
    fetch(`${BASE}/roles/${id}`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<RoleDetail>),

  setPermissions: (id: number, grants: Grant[], version: number, token: string) =>
    fetch(`${BASE}/roles/${id}/permissions`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify({ grants, version }) }).then(handle<RoleDetail>),

  setStatus: (id: number, active: boolean, version: number, token: string) =>
    fetch(`${BASE}/roles/${id}/status`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify({ active, version }) }).then(handle<RoleDetail>),

  /** `removeFromUsers`: take the role off every holder first (each is signed out), then delete. */
  remove: (id: number, token: string, removeFromUsers = false) =>
    fetch(`${BASE}/roles/${id}${removeFromUsers ? '?removeFromUsers=true' : ''}`, { method: 'DELETE', headers: authHeaders(token) }).then(handle<null>),

  addMember: (id: number, userId: string, token: string) =>
    fetch(`${BASE}/roles/${id}/members`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ userId }) }).then(handle<RoleDetail>),

  removeMember: (id: number, userId: string, token: string) =>
    fetch(`${BASE}/roles/${id}/members/${userId}`, { method: 'DELETE', headers: authHeaders(token) }).then(handle<RoleDetail>),

  history: (id: number, token: string) =>
    fetch(`${BASE}/roles/${id}/history`, { headers: authHeaders(token) }).then(handle<RoleHistoryEntry[]>),

  /** Reverse lookup: who holds module:action through an active role. */
  holders: (module: string, action: string, token: string) =>
    fetch(`${BASE}/permissions/holders?module=${encodeURIComponent(module)}&action=${encodeURIComponent(action)}`, { headers: authHeaders(token) }).then(handle<PermissionHolder[]>),

  /** Add or remove one role for several users; each user succeeds or fails on its own. */
  bulkMembers: (id: number, userIds: string[], remove: boolean, token: string) =>
    fetch(`${BASE}/roles/${id}/members/bulk`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ userIds, remove }) }).then(handle<BulkMemberResult[]>),

  userRoles: (userId: string, token: string) =>
    fetch(`${BASE}/users/${userId}/roles`, { headers: authHeaders(token) }).then(handle<UserRoles>),

  setUserRoles: (userId: string, roleIds: number[], token: string) =>
    fetch(`${BASE}/users/${userId}/roles`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify({ roleIds }) }).then(handle<UserRoles>),
};
