import { API_ORIGIN } from './config';

const BASE = `${API_ORIGIN}/api/helpdesk`;
const HR_BASE = `${API_ORIGIN}/api/hr/helpdesk`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

function bearerOnly(token: string) {
  // No Content-Type here — the browser sets multipart/form-data with the correct boundary itself.
  return { Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error((body as any).message ?? `Request failed (${res.status})`);
  return body as T;
}

export type TicketStatus = 'OPEN' | 'ASSIGNED' | 'IN_PROGRESS' | 'WAITING_FOR_EMPLOYEE' | 'RESOLVED' | 'CLOSED';
export type TicketPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';

export interface HelpdeskCategory {
  id: number;
  name: string;
}

export interface TicketSummary {
  id: string;
  ticketNumber: string;
  categoryName: string;
  status: TicketStatus;
  priority: TicketPriority;
  employeeUserId: string;
  employeeName: string;
  assignedTo: string | null;
  assignedToName: string | null;
  replyCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface ReplyItem {
  id: string;
  senderId: string;
  senderName: string;
  senderRole: 'EMPLOYEE' | 'HR';
  message: string;
  internal: boolean;
  hasAttachment: boolean;
  attachmentName: string | null;
  attachmentUrl: string | null;
  createdAt: string;
}

export interface TicketDetail {
  id: string;
  ticketNumber: string;
  categoryId: number;
  categoryName: string;
  description: string;
  status: TicketStatus;
  priority: TicketPriority;
  employeeUserId: string;
  employeeName: string;
  assignedTo: string | null;
  assignedToName: string | null;
  resolvedAt: string | null;
  resolvedByName: string | null;
  createdAt: string;
  updatedAt: string;
  replies: ReplyItem[];
}

export interface PagedTickets {
  content: TicketSummary[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface HelpdeskDashboard {
  openCount: number;
  assignedCount: number;
  inProgressCount: number;
  waitingForEmployeeCount: number;
  resolvedCount: number;
  closedCount: number;
}

export interface AssignableAgent {
  userId: string;
  name: string;
}

function qs(params: Record<string, string | number | undefined>) {
  const parts = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ── Employee-facing ("My Requests" under Help & Guidance) ─────────────────

export const helpdeskApi = {
  listCategories: (token: string) =>
    fetch(`${BASE}/categories`, { headers: authHeaders(token) }).then(handle<HelpdeskCategory[]>),

  createTicket: (payload: { categoryId: number; description: string }, token: string) =>
    fetch(BASE, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<TicketDetail>),

  listMine: (token: string, opts: { status?: string; search?: string; page?: number; size?: number } = {}) =>
    fetch(`${BASE}/my${qs({ status: opts.status, search: opts.search, page: opts.page ?? 0, size: opts.size ?? 10 })}`,
      { headers: authHeaders(token) }).then(handle<PagedTickets>),

  getTicket: (id: string, token: string) =>
    fetch(`${BASE}/${id}`, { headers: authHeaders(token) }).then(handle<TicketDetail>),

  reply: (id: string, message: string, attachment: File | null, token: string) => {
    const form = new FormData();
    form.append('message', message);
    if (attachment) form.append('attachment', attachment);
    return fetch(`${BASE}/${id}/reply`, { method: 'POST', headers: bearerOnly(token), body: form }).then(handle<ReplyItem>);
  },

  attachmentDownloadUrl: (replyId: string) => `${BASE}/replies/${replyId}/attachment`,

  downloadAttachment: async (replyId: string, token: string) => {
    const res = await fetch(`${BASE}/replies/${replyId}/attachment`, { headers: bearerOnly(token) });
    if (!res.ok) throw new Error(`Download failed (${res.status})`);
    return res.blob();
  },
};

// ── HR Admin ("HR Service Requests" queue) ─────────────────────────────────

export const hrHelpdeskApi = {
  listQueue: (token: string, opts: { status?: string; assignedTo?: string; search?: string; page?: number; size?: number } = {}) =>
    fetch(`${HR_BASE}${qs({ status: opts.status, assignedTo: opts.assignedTo, search: opts.search, page: opts.page ?? 0, size: opts.size ?? 10 })}`,
      { headers: authHeaders(token) }).then(handle<PagedTickets>),

  dashboard: (token: string) =>
    fetch(`${HR_BASE}/dashboard`, { headers: authHeaders(token) }).then(handle<HelpdeskDashboard>),

  listAgents: (token: string) =>
    fetch(`${HR_BASE}/agents`, { headers: authHeaders(token) }).then(handle<AssignableAgent[]>),

  getTicket: (id: string, token: string) =>
    fetch(`${HR_BASE}/${id}`, { headers: authHeaders(token) }).then(handle<TicketDetail>),

  updateStatus: (id: string, payload: { status: TicketStatus; comment?: string }, token: string) =>
    fetch(`${HR_BASE}/${id}/status`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<TicketDetail>),

  assign: (id: string, assigneeUserId: string, token: string) =>
    fetch(`${HR_BASE}/${id}/assign`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify({ assigneeUserId }) }).then(handle<TicketDetail>),

  reply: (id: string, message: string, internal: boolean, attachment: File | null, token: string) => {
    const form = new FormData();
    form.append('message', message);
    form.append('internal', String(internal));
    if (attachment) form.append('attachment', attachment);
    return fetch(`${HR_BASE}/${id}/reply`, { method: 'POST', headers: bearerOnly(token), body: form }).then(handle<ReplyItem>);
  },
};
