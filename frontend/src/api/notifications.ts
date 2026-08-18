import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/notifications`;

export interface NotificationItem {
  id: number;
  type: string;
  title: string;
  message: string;
  linkPath: string | null;
  read: boolean;
  priority: string;
  createdAt: string;
}

export interface PagedNotifications {
  content: NotificationItem[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

async function handle<T>(res: Response): Promise<T> {
  let body: unknown = {};
  try { body = await res.json(); } catch { /* non-JSON */ }
  if (!res.ok) throw new Error((body as { message?: string }).message ?? 'Request failed');
  return body as T;
}

export const notificationsApi = {
  list: (token: string, page = 0, size = 20) =>
    fetch(`${BASE}?page=${page}&size=${size}`, {
      headers: { Authorization: `Bearer ${token}` },
    }).then(handle<PagedNotifications>),

  unread: (token: string, page = 0, size = 8) =>
    fetch(`${BASE}/unread?page=${page}&size=${size}`, {
      headers: { Authorization: `Bearer ${token}` },
    }).then(handle<PagedNotifications>),

  unreadCount: (token: string) =>
    fetch(`${BASE}/unread-count`, {
      headers: { Authorization: `Bearer ${token}` },
    }).then(handle<{ count: number }>),

  markRead: (token: string, id: number) =>
    fetch(`${BASE}/${id}/read`, {
      method: 'PATCH',
      headers: { Authorization: `Bearer ${token}` },
    }),

  markAllRead: (token: string) =>
    fetch(`${BASE}/read-all`, {
      method: 'PATCH',
      headers: { Authorization: `Bearer ${token}` },
    }),
};
