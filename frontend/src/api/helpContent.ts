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

export interface HelpContentSummary {
  id: string;
  type: HelpContentType;
  title: string;
  description: string | null;
  category: string | null;
  published: boolean;
  active: boolean;
  featured: boolean;
  displayOrder: number;
  viewCount: number;
  hasAttachment: boolean;
  attachmentName: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface HelpContentDetail extends HelpContentSummary {
  body: string | null;
  publishedAt: string | null;
  attachmentUrl: string | null;
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

// ── Employee-facing (read-only: search/browse published content) ──────────

export const helpContentApi = {
  list: (token: string, opts: { type?: string; category?: string; search?: string; sort?: 'popular' | 'recent'; page?: number; size?: number } = {}) =>
    fetch(`${BASE}${qs({ type: opts.type, category: opts.category, search: opts.search, sort: opts.sort, page: opts.page ?? 0, size: opts.size ?? 20 })}`,
      { headers: authHeaders(token) }).then(handle<PagedHelpContent>),

  getOne: (id: string, token: string) =>
    fetch(`${BASE}/${id}`, { headers: authHeaders(token) }).then(handle<HelpContentDetail>),

  trackView: (id: string, token: string) =>
    fetch(`${BASE}/${id}/view`, { method: 'POST', headers: authHeaders(token) }).catch(() => { /* best-effort */ }),

  attachmentUrl: (id: string) => `${BASE}/${id}/attachment`,

  downloadAttachment: async (id: string, token: string) => {
    const res = await fetch(`${BASE}/${id}/attachment`, { headers: bearerOnly(token) });
    if (!res.ok) throw new Error(`Download failed (${res.status})`);
    return res.blob();
  },
};

// ── HR Admin (content management) ──────────────────────────────────────────

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

  publish: (id: string, token: string) =>
    fetch(`${HR_BASE}/${id}/publish`, { method: 'POST', headers: authHeaders(token) }).then(handle<HelpContentDetail>),

  unpublish: (id: string, token: string) =>
    fetch(`${HR_BASE}/${id}/unpublish`, { method: 'POST', headers: authHeaders(token) }).then(handle<HelpContentDetail>),

  archive: (id: string, token: string) =>
    fetch(`${HR_BASE}/${id}/archive`, { method: 'POST', headers: authHeaders(token) }).then(handle<HelpContentDetail>),

  reactivate: (id: string, token: string) =>
    fetch(`${HR_BASE}/${id}/reactivate`, { method: 'POST', headers: authHeaders(token) }).then(handle<HelpContentDetail>),

  remove: (id: string, token: string) =>
    fetch(`${HR_BASE}/${id}`, { method: 'DELETE', headers: authHeaders(token) }).then(res => {
      if (!res.ok) throw new Error(`Delete failed (${res.status})`);
    }),

  uploadAttachment: (id: string, file: File, token: string) => {
    const form = new FormData();
    form.append('file', file);
    return fetch(`${HR_BASE}/${id}/attachment`, { method: 'POST', headers: bearerOnly(token), body: form }).then(handle<HelpContentDetail>);
  },
};
