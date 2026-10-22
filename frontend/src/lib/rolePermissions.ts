import type { CatalogueModule, Grant } from '../api/roles';
import type { MyPermissionsResponse } from '../api/me';
import { hasPermission } from './permissions';

/** Selection state of the role editor: "module:ACTION" -> granted scope. Absent = not granted. */
export type GrantMap = Record<string, string>;

export const ACTION_LABEL: Record<string, string> = {
  VIEW: 'View', CREATE: 'Create', EDIT: 'Edit', DELETE: 'Delete',
  APPROVE: 'Approve', REVERT: 'Revert', EXPORT: 'Export', CONFIGURE: 'Configure',
};

export const SCOPE_LABEL: Record<string, string> = {
  OWN: 'Own', TEAM: 'Team', DEPARTMENT: 'Department', LOCATION: 'Location', ALL: 'Organization',
};

/** Scopes offered in the editor, narrowest first. DEPARTMENT/LOCATION have no data filtering
 *  behind them yet (see ScopeCoverage), so they're only shown when a role already holds them. */
const OFFERED_SCOPES = ['OWN', 'TEAM', 'ALL'];

export const grantKey = (module: string, action: string) => `${module}:${action}`;

export function toGrantMap(grants: Grant[]): GrantMap {
  const map: GrantMap = {};
  for (const g of grants) map[grantKey(g.module, g.action)] = g.scope;
  return map;
}

export function fromGrantMap(map: GrantMap): Grant[] {
  return Object.entries(map)
    .map(([key, scope]) => {
      const [module, action] = key.split(':');
      return { module, action, scope };
    })
    .sort((a, b) => grantKey(a.module, a.action).localeCompare(grantKey(b.module, b.action)));
}

/** Grants for configurable modules only — the server rejects any other module, and leaves its
 *  existing rows untouched, so they're never sent. */
export function toGrants(map: GrantMap, catalogue: CatalogueModule[]): Grant[] {
  const configurable = new Set(catalogue.filter((m) => m.configurable).map((m) => m.code));
  return fromGrantMap(map).filter((g) => configurable.has(g.module));
}

/** The scopes this administrator may grant for module:action — only what they hold themselves
 *  (mirrors the server's privilege ceiling, which is the real check). */
export function grantableScopes(mine: MyPermissionsResponse | null | undefined, module: string, action: string): string[] {
  return OFFERED_SCOPES.filter((s) => hasPermission(mine, module, action, s));
}

export type CategoryState = 'none' | 'some' | 'all';

/** Parent checkbox state, counting only the actions this admin can actually toggle. */
export function categoryState(map: GrantMap, module: string, actions: string[]): CategoryState {
  if (actions.length === 0) return 'none';
  const on = actions.filter((a) => map[grantKey(module, a)]).length;
  return on === 0 ? 'none' : on === actions.length ? 'all' : 'some';
}

/**
 * Select-all / clear-all for ONE module. Only `actions` (the visible, grantable ones) change —
 * never another module, never an action the admin can't grant. Selecting keeps an existing scope
 * and otherwise uses `defaultScope(action)`; an action with no default is left alone.
 */
export function setCategory(map: GrantMap, module: string, actions: string[], on: boolean,
                            defaultScope: (action: string) => string | undefined): GrantMap {
  const next = { ...map };
  for (const a of actions) {
    const k = grantKey(module, a);
    if (!on) { delete next[k]; continue; }
    if (next[k]) continue;
    const scope = defaultScope(a);
    if (scope) next[k] = scope;
  }
  return next;
}

export function setGrant(map: GrantMap, module: string, action: string, scope: string | null): GrantMap {
  const next = { ...map };
  const k = grantKey(module, action);
  if (scope) next[k] = scope; else delete next[k];
  return next;
}

export function sameGrants(a: GrantMap, b: GrantMap): boolean {
  const ka = Object.keys(a);
  return ka.length === Object.keys(b).length && ka.every((k) => a[k] === b[k]);
}

/** Case-insensitive match on the module name or any of its action labels. */
export function filterCatalogue(catalogue: CatalogueModule[], query: string): CatalogueModule[] {
  const q = query.trim().toLowerCase();
  if (!q) return catalogue;
  return catalogue.filter((m) => m.name.toLowerCase().includes(q) || m.code.includes(q)
    || m.actions.some((a) => (ACTION_LABEL[a] ?? a).toLowerCase().includes(q)));
}

/** "12 permissions · 4 modules" style summary. */
export function summarizeGrants(grants: Grant[]): string {
  const modules = new Set(grants.map((g) => g.module)).size;
  if (grants.length === 0) return 'No permissions';
  return `${grants.length} permission${grants.length === 1 ? '' : 's'} · ${modules} module${modules === 1 ? '' : 's'}`;
}
