import { API_ORIGIN } from './config';

const BASE = `${API_ORIGIN}/api/search`;

function authHeaders(token: string) {
  return { Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error((body as { message?: string }).message ?? `Request failed (${res.status})`);
  return body as T;
}

export interface SearchResultItem {
  module: string;
  id: string;
  title: string;
  subtitle: string | null;
  detailUrl: string;
}

export interface SearchGroup {
  module: string;
  label: string;
  items: SearchResultItem[];
  totalCount: number;
  refineUrl: string | null;
}

export interface SearchPreview {
  query: string;
  groups: SearchGroup[];
}

export interface SearchPage {
  module: string;
  label: string;
  items: SearchResultItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  refineUrl: string | null;
}

function qs(params: Record<string, string | number | undefined>) {
  const parts = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export const searchApi = {
  /** Header search dropdown — top matches per module. */
  preview: (token: string, q: string) =>
    fetch(`${BASE}${qs({ q })}`, { headers: authHeaders(token) }).then(handle<SearchPreview>),

  /** Dedicated /search results page — one module's independently-paginated results. */
  modulePage: (token: string, module: string, q: string, page = 0, size = 10) =>
    fetch(`${BASE}/${module}${qs({ q, page, size })}`, { headers: authHeaders(token) }).then(handle<SearchPage>),
};
