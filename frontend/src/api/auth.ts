const BASE = '/api';

export interface LoginResponse {
  token: string;
  mustChangePassword: boolean;
  email: string;
  role?: string;
}

export interface ChangePasswordResponse {
  token: string;
  message: string;
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try {
    body = await res.json();
  } catch {
    // non-JSON response
  }
  if (!res.ok) {
    throw new Error(body.message ?? 'Request failed');
  }
  return body as T;
}

export const authApi = {
  login: (email: string, password: string) =>
    fetch(`${BASE}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    }).then(handle<LoginResponse>),

  changePassword: (
    currentPassword: string,
    newPassword: string,
    confirmPassword: string,
    token: string
  ) =>
    fetch(`${BASE}/auth/change-password`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ currentPassword, newPassword, confirmPassword }),
    }).then(handle<ChangePasswordResponse>),
};
