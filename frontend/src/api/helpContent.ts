import { API_ORIGIN } from './config';

const BASE = `${API_ORIGIN}/api/help-content`;
const HR_BASE = `${API_ORIGIN}/api/hr/help-content`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

function bearerOnly(token: string) {
  return { Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error((body as any).message ?? `Request failed (${res.status})`);
  return body as T;
}

export type HelpContentType = 'FAQ' | 'QUICK_HELP' | 'GUIDE' | 'DOCUMENT';

// Mirrors HelpContentService's MAX_ATTACHMENTS_PER_CONTENT / MAX_ATTACHMENT_SIZE_BYTES /
// ALLOWED_ATTACHMENT_EXTENSIONS — kept here as the single frontend source so client-side
// validation gives immediate feedback, with the backend as the authoritative enforcement.
export const MAX_ATTACHMENTS_PER_CONTENT = 5;
export const MAX_ATTACHMENT_SIZE_BYTES = 10 * 1024 * 1024;
export const ALLOWED_ATTACHMENT_EXTENSIONS = ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'png', 'jpg', 'jpeg', 'gif', 'txt', 'csv'];

export function validateAttachmentFile(file: File): string | null {
  const ext = file.name.split('.').pop()?.toLowerCase() ?? '';
  if (!ALLOWED_ATTACHMENT_EXTENSIONS.includes(ext)) {
    return `"${file.name}": unsupported file type (.${ext || 'unknown'})`;
  }
  if (file.size > MAX_ATTACHMENT_SIZE_BYTES) {
    return `"${file.name}" exceeds the ${MAX_ATTACHMENT_SIZE_BYTES / (1024 * 1024)}MB attachment size limit`;
  }
  return null;
}

// Six-state lifecycle — see HelpContentService for the transition rules. Employees only ever
// see PUBLISHED; every other status is HR/Super Admin-only.
export type HelpContentStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'PUBLISHED' | 'UNPUBLISHED' | 'ARCHIVED';

// Chosen in the Review & Publish flow (see ReviewPublishModal), not the Add/Edit form —
// publishing is an authorization/visibility decision, not a content-editing one. Mirrors the
// same 4-bucket collapse the nav already uses for the underlying Role codes (HR_ADMIN -> HR,
// SUPER_ADMIN -> ADMIN) — see backend RoleUtils.audienceBuckets.
export type Audience = 'EMPLOYEE' | 'MANAGER' | 'HR' | 'ADMIN';
export const AUDIENCE_OPTIONS: Audience[] = ['EMPLOYEE', 'MANAGER', 'HR', 'ADMIN'];
export const AUDIENCE_LABEL: Record<Audience, string> = {
  EMPLOYEE: 'Employee', MANAGER: 'Manager', HR: 'HR', ADMIN: 'Admin',
};

export interface Attachment {
  id: string;
  fileName: string;
  fileType: string | null;
  fileSize: number | null;
  displayOrder: number;
}

export interface HelpContentSummary {
  id: string;
  type: HelpContentType;
  title: string;
  description: string | null;
  category: string | null;
  status: HelpContentStatus;
  featured: boolean;
  displayOrder: number;
  viewCount: number;
  attachmentCount: number;
  // Empty/absent = visible to everyone once published.
  audience: Audience[];
  rejectionReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface HelpContentDetail extends Omit<HelpContentSummary, 'attachmentCount'> {
  body: string | null;
  publishedAt: string | null;
  attachments: Attachment[];
  createdByName: string;
  updatedByName: string | null;
}

export interface PagedHelpContent {
  content: HelpContentSummary[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

function qs(params: Record<string, string | number | undefined>) {
  const parts = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ── Employee-facing (read-only: search/browse published content, plus approval decisions —
//    see backend HelpContentController javadoc for why approve/reject live on this API surface
//    rather than the HR-admin-only one: the resolved approver is often a plain manager) ──────

export const helpContentApi = {
  list: (token: string, opts: { type?: string; category?: string; search?: string; sort?: 'popular' | 'recent'; page?: number; size?: number } = {}) =>
    fetch(`${BASE}${qs({ type: opts.type, category: opts.category, search: opts.search, sort: opts.sort, page: opts.page ?? 0, size: opts.size ?? 20 })}`,
      { headers: authHeaders(token) }).then(handle<PagedHelpContent>),

  getOne: (id: string, token: string) =>
    fetch(`${BASE}/${id}`, { headers: authHeaders(token) }).then(handle<HelpContentDetail>),

  trackView: (id: string, token: string) =>
    fetch(`${BASE}/${id}/view`, { method: 'POST', headers: authHeaders(token) }).catch(() => { /* best-effort */ }),

  listAttachments: (id: string, token: string) =>
    fetch(`${BASE}/${id}/attachments`, { headers: authHeaders(token) }).then(handle<Attachment[]>),

  attachmentUrl: (id: string, attachmentId: string) => `${BASE}/${id}/attachments/${attachmentId}`,

  downloadAttachment: async (id: string, attachmentId: string, token: string) => {
    const res = await fetch(`${BASE}/${id}/attachments/${attachmentId}`, { headers: bearerOnly(token) });
    if (!res.ok) throw new Error(`Download failed (${res.status})`);
    return res.blob();
  },
};

// ── HR Admin (content authoring/lifecycle management) ──────────────────────

export const hrHelpContentApi = {
  list: (token: string, opts: { type?: string; category?: string; search?: string; page?: number; size?: number } = {}) =>
    fetch(`${HR_BASE}${qs({ type: opts.type, category: opts.category, search: opts.search, page: opts.page ?? 0, size: opts.size ?? 20 })}`,
      { headers: authHeaders(token) }).then(handle<PagedHelpContent>),

  getOne: (id: string, token: string) =>
    fetch(`${HR_BASE}/${id}`, { headers: authHeaders(token) }).then(handle<HelpContentDetail>),

  create: (payload: { type: HelpContentType; title: string; description?: string; body?: string; category?: string; featured?: boolean; displayOrder?: number }, token: string) =>
    fetch(HR_BASE, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<HelpContentDetail>),

  update: (id: string, payload: { title: string; description?: string; body?: string; category?: string; featured?: boolean; displayOrder?: number }, token: string) =>
    fetch(`${HR_BASE}/${id}`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<HelpContentDetail>),

  submit: (id: string, token: string) =>
    fetch(`${HR_BASE}/${id}/submit`, { method: 'POST', headers: authHeaders(token) }).then(handle<HelpContentDetail>),

  withdraw: (id: string, reason: string, token: string) =>
    fetch(`${HR_BASE}/${id}/withdraw`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ reason }) }).then(handle<HelpContentDetail>),

  publish: (id: string, audience: Audience[], token: string) =>
    fetch(`${HR_BASE}/${id}/publish`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ audience }) }).then(handle<HelpContentDetail>),

  unpublish: (id: string, token: string) =>
    fetch(`${HR_BASE}/${id}/unpublish`, { method: 'POST', headers: authHeaders(token) }).then(handle<HelpContentDetail>),

  archive: (id: string, token: string) =>
    fetch(`${HR_BASE}/${id}/archive`, { method: 'POST', headers: authHeaders(token) }).then(handle<HelpContentDetail>),

  restore: (id: string, token: string) =>
    fetch(`${HR_BASE}/${id}/restore`, { method: 'POST', headers: authHeaders(token) }).then(handle<HelpContentDetail>),

  remove: (id: string, token: string) =>
    fetch(`${HR_BASE}/${id}`, { method: 'DELETE', headers: authHeaders(token) }).then(res => {
      if (!res.ok) throw new Error(`Delete failed (${res.status})`);
    }),

  addAttachment: (id: string, file: File, token: string) => {
    const form = new FormData();
    form.append('file', file);
    return fetch(`${HR_BASE}/${id}/attachments`, { method: 'POST', headers: bearerOnly(token), body: form }).then(handle<HelpContentDetail>);
  },

  /** Multiple files selected/uploaded in one action. */
  addAttachments: (id: string, files: File[], token: string) => {
    const form = new FormData();
    files.forEach(f => form.append('files', f));
    return fetch(`${HR_BASE}/${id}/attachments/batch`, { method: 'POST', headers: bearerOnly(token), body: form }).then(handle<HelpContentDetail>);
  },

  removeAttachment: (id: string, attachmentId: string, token: string) =>
    fetch(`${HR_BASE}/${id}/attachments/${attachmentId}`, { method: 'DELETE', headers: authHeaders(token) }).then(handle<HelpContentDetail>),

  replaceAttachment: (id: string, attachmentId: string, file: File, token: string) => {
    const form = new FormData();
    form.append('file', file);
    return fetch(`${HR_BASE}/${id}/attachments/${attachmentId}`, { method: 'PUT', headers: bearerOnly(token), body: form }).then(handle<HelpContentDetail>);
  },

  reorderAttachments: (id: string, attachmentIds: string[], token: string) =>
    fetch(`${HR_BASE}/${id}/attachments/order`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify({ attachmentIds }) }).then(handle<HelpContentDetail>),
};
