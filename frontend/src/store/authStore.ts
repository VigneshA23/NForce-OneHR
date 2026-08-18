import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';

export interface AuthUser {
  email: string;
  fullName?: string;
  mustChangePassword: boolean;
  role?: string; // DB enum e.g. SUPER_ADMIN, HR_ADMIN, EMPLOYEE
}

interface AuthState {
  token: string | null;
  user: AuthUser | null;
  setAuth: (token: string, user: AuthUser) => void;
  clearAuth: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      user: null,
      setAuth: (token, user) => set({ token, user }),
      clearAuth: () => set({ token: null, user: null }),
    }),
    { name: 'onehr-auth', storage: createJSONStorage(() => sessionStorage) }
  )
);
