const BASE = '/api/audit-logs';

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error(body.message ?? `Request failed (${res.status})`);
  return body as T;
}

export type ActionCategory = 'HR_OPERATIONAL' | 'ACCESS_CONTROL';
export type ActionGroup = 'EMPLOYEE' | 'ATTENDANCE' | 'LEAVE' | 'EXPENSE' | 'ASSET' | 'ACCESS' | 'OTHER';

export interface AuditLogEntry {
  id: number;
  occurredAt: string;
  actorId: string | null;
  actorName: string | null;
  actorEmail: string | null;
  action: string;
  actionCategory: ActionCategory;
  actionGroup: ActionGroup;
  targetId: string | null;
  targetLabel: string;
  beforeState: string | null;
  afterState: string | null;
}

export interface PagedAuditLogs {
  content: AuditLogEntry[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface AuditLogStats {
  totalCount: number;
  todayCount: number;
  /** Keys: EMPLOYEE, ATTENDANCE, LEAVE, EXPENSE, ASSET, ACCESS (Super Admin only), OTHER. */
  byGroup: Record<string, number>;
}

export interface AuditLogFilters {
  actorSearch?: string;
  targetSearch?: string;
  action?: string;
  group?: ActionGroup;
  from?: string; // yyyy-MM-dd
  to?: string;   // yyyy-MM-dd
}

function buildQuery(filters: AuditLogFilters, extra?: Record<string, string | number>): string {
  const params = new URLSearchParams();
  if (filters.actorSearch)  params.set('actorSearch', filters.actorSearch);
  if (filters.targetSearch) params.set('targetSearch', filters.targetSearch);
  if (filters.action)       params.set('action', filters.action);
  if (filters.group)        params.set('group', filters.group);
  // Backend binds from/to as LocalDateTime — expand the date-only picker value to day bounds.
  if (filters.from)         params.set('from', `${filters.from}T00:00:00`);
  if (filters.to)           params.set('to', `${filters.to}T23:59:59`);
  if (extra) for (const [k, v] of Object.entries(extra)) params.set(k, String(v));
  const qs = params.toString();
  return qs ? `?${qs}` : '';
}

export const auditApi = {
  list: (filters: AuditLogFilters, page: number, size: number, token: string) =>
    fetch(`${BASE}${buildQuery(filters, { page, size })}`, { headers: authHeaders(token) })
      .then(handle<PagedAuditLogs>),

  stats: (filters: AuditLogFilters, token: string) =>
    fetch(`${BASE}/stats${buildQuery(filters)}`, { headers: authHeaders(token) })
      .then(handle<AuditLogStats>),

  // Unpaginated — backs the "export matches current filters exactly" requirement.
  exportAll: (filters: AuditLogFilters, token: string) =>
    fetch(`${BASE}/export${buildQuery(filters)}`, { headers: authHeaders(token) })
      .then(handle<AuditLogEntry[]>),
};
