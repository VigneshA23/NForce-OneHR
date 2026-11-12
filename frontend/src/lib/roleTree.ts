import type { CatalogueAction, CatalogueModule, CatalogueNode } from '../api/roles';
import type { MyPermissionsResponse } from '../api/me';
import { ACTION_LABEL, grantKey, grantableScopes, type CategoryState, type GrantMap } from './rolePermissions';

// Hierarchical catalogue (Module > Submodule > Section).
//
// Grants stay flat ("module:ACTION" -> scope) and live on LEAF nodes only; a parent checkbox is nothing
// more than a bulk edit of the leaves beneath it, so the editor always shows exactly the individual grants
// that will be saved, and there is no inheritance to reason about.

const SCOPE_RANK: Record<string, number> = { OWN: 0, TEAM: 1, ALL: 2 };
const OFFERED: string[] = ['OWN', 'TEAM', 'ALL'];

/** Leaves under (and including) `node` that carry actions. */
export function leavesOf(node: CatalogueNode): CatalogueNode[] {
  const own = node.actions.length > 0 ? [node] : [];
  return [...own, ...node.children.flatMap(leavesOf)];
}

/** The flat per-module list the other role tools (compare, who-has-permission, history names) use. */
export function flattenCatalogue(tree: CatalogueNode[]): CatalogueModule[] {
  return tree.flatMap(leavesOf).map((n) => ({
    code: n.code, name: n.name, configurable: n.configurable, actions: n.actions.map((a) => a.action),
  }));
}

/**
 * Scopes this administrator may pick for one action: inside the range the backend evaluates for it AND
 * something they may grant themselves. A scope already on the role but outside that (legacy, or granted
 * before the range existed) stays visible so it is never silently dropped.
 */
export function scopeChoices(action: CatalogueAction, grantable: string[], current?: string): string[] {
  const lo = SCOPE_RANK[action.minScope] ?? 0;
  const hi = SCOPE_RANK[action.maxScope] ?? 2;
  const ok = OFFERED.filter((s) => SCOPE_RANK[s] >= lo && SCOPE_RANK[s] <= hi && grantable.includes(s));
  return current && !ok.includes(current) ? [...ok, current] : ok;
}

/** Scope used when a whole group is ticked: the NARROWEST supported one, never silently the widest. */
export function bulkScope(action: CatalogueAction, grantable: string[]): string | undefined {
  return scopeChoices(action, grantable)[0];
}

type Pair = { leaf: CatalogueNode; action: CatalogueAction };

/** (leaf, action) pairs the admin can actually toggle under `node` (configurable leaf, at least one allowed scope). */
function togglePairs(node: CatalogueNode, mine: MyPermissionsResponse | null | undefined): Pair[] {
  return leavesOf(node)
    .filter((l) => l.configurable)
    .flatMap((leaf) => leaf.actions
      .filter((a) => scopeChoices(a, grantableScopes(mine, leaf.code, a.action)).length > 0)
      .map((action) => ({ leaf, action })));
}

/** Tri-state of a parent (or leaf) over the permissions the admin can toggle beneath it. */
export function nodeState(map: GrantMap, node: CatalogueNode, mine: MyPermissionsResponse | null | undefined): CategoryState {
  const pairs = togglePairs(node, mine);
  if (pairs.length === 0) return 'none';
  const on = pairs.filter((p) => map[grantKey(p.leaf.code, p.action.action)]).length;
  return on === 0 ? 'none' : on === pairs.length ? 'all' : 'some';
}

/**
 * Tick / clear everything under `node`. Ticking adds only what is not yet granted, at the narrowest supported
 * scope, and SKIPS high-impact (sensitive) leaves: those must be ticked on their own, deliberately. Clearing
 * removes every grant beneath the node that the admin may remove, including sensitive ones. Nothing outside
 * the node is ever touched.
 */
export function setNode(map: GrantMap, node: CatalogueNode, on: boolean, mine: MyPermissionsResponse | null | undefined): GrantMap {
  const next = { ...map };
  for (const { leaf, action } of togglePairs(node, mine)) {
    const k = grantKey(leaf.code, action.action);
    if (!on) { delete next[k]; continue; }
    if (next[k] || leaf.sensitive) continue;
    const scope = bulkScope(action, grantableScopes(mine, leaf.code, action.action));
    if (scope) next[k] = scope;
  }
  return next;
}

/** How many high-impact permissions under `node` a bulk tick would leave alone (a hint on the parent row). */
export function sensitiveSkipped(map: GrantMap, node: CatalogueNode, mine: MyPermissionsResponse | null | undefined): number {
  return togglePairs(node, mine).filter((p) => p.leaf.sensitive && !map[grantKey(p.leaf.code, p.action.action)]).length;
}

/** granted / total actions under `node` (every action, for the badge, not only the toggleable ones). */
export function nodeCounts(map: GrantMap, node: CatalogueNode): { granted: number; total: number } {
  const leaves = leavesOf(node);
  return {
    granted: leaves.reduce((n, l) => n + l.actions.filter((a) => map[grantKey(l.code, a.action)]).length, 0),
    total: leaves.reduce((n, l) => n + l.actions.length, 0),
  };
}

/** Codes of every high-impact leaf, which the save confirmation watches. */
export function sensitiveCodes(tree: CatalogueNode[]): Set<string> {
  return new Set(tree.flatMap(leavesOf).filter((l) => l.sensitive).map((l) => l.code));
}

function nodeMatches(n: CatalogueNode, q: string): boolean {
  return n.name.toLowerCase().includes(q) || n.code.toLowerCase().includes(q) || n.description.toLowerCase().includes(q)
    || n.actions.some((a) => (ACTION_LABEL[a.action] ?? a.action).toLowerCase().includes(q));
}

/** Keeps nodes that match the query (with everything beneath them) or that contain a match. */
export function filterTree(tree: CatalogueNode[], query: string): CatalogueNode[] {
  const q = query.trim().toLowerCase();
  if (!q) return tree;
  const walk = (nodes: CatalogueNode[]): CatalogueNode[] => nodes.flatMap((n) => {
    if (nodeMatches(n, q)) return [n];
    const kids = walk(n.children);
    return kids.length ? [{ ...n, children: kids }] : [];
  });
  return walk(tree);
}

/** Read-only view of a role: only the leaves that actually hold a grant, with their parents. */
export function pruneToGranted(tree: CatalogueNode[], map: GrantMap): CatalogueNode[] {
  const walk = (nodes: CatalogueNode[]): CatalogueNode[] => nodes.flatMap((n) => {
    if (n.actions.length > 0) return n.actions.some((a) => map[grantKey(n.code, a.action)]) ? [n] : [];
    const kids = walk(n.children);
    return kids.length ? [{ ...n, children: kids }] : [];
  });
  return walk(tree);
}

/** Every node code (parents and leaves), for "Expand all". */
export function allNodeCodes(tree: CatalogueNode[]): string[] {
  return tree.flatMap((n) => [n.code, ...allNodeCodes(n.children)]);
}

/** Added / removed high-impact permissions between two grant maps, as human-readable names. */
export function sensitiveDiff(before: GrantMap, after: GrantMap, tree: CatalogueNode[]): { added: string[]; removed: string[] } {
  const high = sensitiveCodes(tree);
  const names = new Map(tree.flatMap(leavesOf).map((l) => [l.code, l.name] as const));
  const label = (k: string) => `${names.get(k.split(':')[0]) ?? k.split(':')[0]}: ${ACTION_LABEL[k.split(':')[1]] ?? k.split(':')[1]}`;
  const isHigh = (k: string) => high.has(k.split(':')[0]);
  return {
    added: Object.keys(after).filter((k) => !before[k] && isHigh(k)).map(label),
    removed: Object.keys(before).filter((k) => !after[k] && isHigh(k)).map(label),
  };
}

/** True when the admin can toggle at least one permission beneath `node`. */
export function hasToggleable(node: CatalogueNode, mine: MyPermissionsResponse | null | undefined): boolean {
  return togglePairs(node, mine).length > 0;
}
