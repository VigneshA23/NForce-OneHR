import type { BulkMemberResult, Grant } from '../api/roles';
import { SCOPE_LABEL } from './rolePermissions';

/** "Copy of X", or "Copy of X (2)", … — unique among the existing role names (case-insensitive), within the 50-char limit. */
export function suggestCloneName(name: string, existingNames: string[]): string {
  const taken = new Set(existingNames.map((n) => n.trim().toLowerCase()));
  const fit = (s: string) => (s.length > 50 ? s.slice(0, 50).trimEnd() : s);
  let candidate = fit(`Copy of ${name}`);
  for (let i = 2; taken.has(candidate.toLowerCase()); i++) {
    const suffix = ` (${i})`;
    candidate = fit(`Copy of ${name}`.slice(0, 50 - suffix.length)) + suffix;
  }
  return candidate;
}

export interface CompareRow {
  module: string;
  action: string;
  a: string | null;
  b: string | null;
  /** same = identical grant; differs = both have it at different scopes; onlyA / onlyB = one side lacks it. */
  status: 'same' | 'differs' | 'onlyA' | 'onlyB';
}

/** Side-by-side grants of two roles, one row per (module, action) either role holds. */
export function compareGrants(a: Grant[], b: Grant[]): CompareRow[] {
  const key = (g: Grant) => `${g.module}:${g.action}`;
  const ma = new Map(a.map((g) => [key(g), g.scope]));
  const mb = new Map(b.map((g) => [key(g), g.scope]));
  const keys = Array.from(new Set([...ma.keys(), ...mb.keys()])).sort();
  return keys.map((k) => {
    const [module, action] = k.split(':');
    const sa = ma.get(k) ?? null;
    const sb = mb.get(k) ?? null;
    const status: CompareRow['status'] = sa && sb ? (sa === sb ? 'same' : 'differs') : sa ? 'onlyA' : 'onlyB';
    return { module, action, a: sa, b: sb, status };
  });
}

/** One-line summary of a bulk membership change: "3 added · 1 failed". */
export function summarizeBulk(results: BulkMemberResult[], remove: boolean): string {
  const ok = results.filter((r) => r.ok && !/^(Already|Didn't)/.test(r.message)).length;
  const unchanged = results.filter((r) => r.ok).length - ok;
  const failed = results.filter((r) => !r.ok).length;
  const parts = [`${ok} ${remove ? 'removed' : 'added'}`];
  if (unchanged) parts.push(`${unchanged} unchanged`);
  if (failed) parts.push(`${failed} failed`);
  return parts.join(' · ');
}

/** "employees:VIEW:ALL" -> "Employees · View · Organization" using the catalogue's module names. */
export function describeAuthority(authority: string, moduleNames: Record<string, string>, actionLabel: Record<string, string>): string {
  const [module, action, scope] = authority.split(':');
  return [moduleNames[module] ?? module, actionLabel[action] ?? action, SCOPE_LABEL[scope] ?? scope].join(' · ');
}
