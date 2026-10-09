import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/birthday-wishes`;

async function handle<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error((body as { message?: string }).message ?? `HTTP ${res.status}`);
  return body as T;
}

export interface BirthdayWish {
  id: number;
  fromUserId: string;
  fromName: string;
  message: string;
  createdAt: string;
}

export interface SendBirthdayWishPayload {
  toUserId: string;
  message: string;
}

export const birthdayWishesApi = {
  send: (payload: SendBirthdayWishPayload, token: string) =>
    fetch(BASE, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(payload),
    }).then(r => handle<BirthdayWish>(r)),

  /** The caller's own wishes received today — backs the celebration card's message list/count. */
  receivedToday: (token: string) =>
    fetch(`${BASE}/received-today`, { headers: { Authorization: `Bearer ${token}` } })
      .then(r => handle<BirthdayWish[]>(r)),
};
