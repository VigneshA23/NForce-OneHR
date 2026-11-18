// Pure helpers for the Super Admin's org-wide "access changes" view (components/audit/AccessChangesView.tsx).

/** The actions the access view can be narrowed to, in the order shown. Mirrors AuditActionCategory on the server. */
export const ACCESS_ACTION_OPTIONS: { value: string; label: string }[] = [
  { value: 'ROLE_PERMISSIONS_UPDATED', label: 'Role permissions changed' },
  { value: 'ROLE_RESTORED', label: 'Role restored to a version' },
  { value: 'ROLE_CHANGED', label: 'Roles assigned to a user' },
  { value: 'ROLE_CREATED', label: 'Role created' },
  { value: 'ROLE_UPDATED', label: 'Role renamed / described' },
  { value: 'ROLE_ACTIVATED', label: 'Role activated' },
  { value: 'ROLE_DEACTIVATED', label: 'Role deactivated' },
  { value: 'ROLE_DELETED', label: 'Role deleted' },
  { value: 'ROLE_SUPER_ADMIN_CHANGED', label: 'Super Admin gained or lost' },
  { value: 'USER_CREATED', label: 'User created' },
  { value: 'USER_ACTIVATED', label: 'User activated' },
  { value: 'USER_DEACTIVATED', label: 'User deactivated' },
  { value: 'USER_SOFT_DELETED', label: 'User removed' },
  { value: 'PASSWORD_RESET', label: 'Password reset by an admin' },
  { value: 'USER_SENSITIVE_DATA_VIEWED', label: 'Sensitive data viewed' },
  { value: 'PROFILE_SENSITIVE_CHANGE_REQUESTED', label: 'Bank / ID change requested' },
  { value: 'PROFILE_SENSITIVE_CHANGE_APPROVED', label: 'Bank / ID change approved' },
  { value: 'PROFILE_SENSITIVE_CHANGE_REJECTED', label: 'Bank / ID change rejected' },
];

export function actionLabel(action: string): string {
  return ACCESS_ACTION_OPTIONS.find((o) => o.value === action)?.label
    ?? action.toLowerCase().replace(/_/g, ' ').replace(/^\w/, (c) => c.toUpperCase());
}

/** yyyy-MM-dd for `days` days before `now`, in the viewer's local calendar. */
export function daysAgo(days: number, now: Date = new Date()): string {
  const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function parse(json: string | null): Record<string, unknown> | null {
  if (!json) return null;
  try { const v = JSON.parse(json); return v && typeof v === 'object' ? v as Record<string, unknown> : null; } catch { return null; }
}

function strings(v: unknown): string[] {
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];
}

/** The reason given for the change (or the reviewer's comment on a decision); empty when none was recorded. */
export function changeReason(afterState: string | null): string {
  const after = parse(afterState);
  for (const key of ['reason', 'reviewComment']) {
    const v = after?.[key];
    if (typeof v === 'string' && v.trim()) return v.trim();
  }
  return '';
}

/** One line on what changed, from the entry's before/after snapshots. Never includes sensitive values: the audit trail holds field names only. */
export function summarizeChange(action: string, beforeState: string | null, afterState: string | null): string {
  const before = parse(beforeState);
  const after = parse(afterState);
  const afterPerms = strings(after?.permissions);
  if (afterPerms.length || strings(before?.permissions).length || action.startsWith('ROLE_PERMISSIONS') || action === 'ROLE_RESTORED') {
    const was = new Set(strings(before?.permissions));
    const now = new Set(afterPerms);
    const added = [...now].filter((p) => !was.has(p)).length;
    const removed = [...was].filter((p) => !now.has(p)).length;
    const base = `${added} added, ${removed} removed`;
    const restoredFrom = after?.restoredFromVersion;
    return typeof restoredFrom === 'number' ? `Restored to version ${restoredFrom} (${base})` : base;
  }
  if (action === 'ROLE_CHANGED') {
    const was = new Set(strings(before?.roles));
    const now = new Set(strings(after?.roles));
    const added = [...now].filter((r) => !was.has(r));
    const removed = [...was].filter((r) => !now.has(r));
    return [added.length ? `+ ${added.join(', ')}` : '', removed.length ? `- ${removed.join(', ')}` : ''].filter(Boolean).join('  ');
  }
  const fields = strings(after?.fields);
  if (fields.length) return `Fields: ${fields.join(', ')}`;
  return '';
}
