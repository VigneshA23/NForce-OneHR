import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import type { MyPermissionsResponse } from '../api/me';

export interface AuthUser {
  email: string;
  fullName?: string;
  mustChangePassword: boolean;
  role?: string; // DB enum e.g. SUPER_ADMIN, HR_ADMIN, EMPLOYEE
  // Base64 data URL of the user's uploaded profile photo (see ProfileService#uploadPhoto),
  // or null/undefined if they haven't uploaded one. Kept here (rather than only in
  // ProfilePage's local state) so the Shell topbar/sidebar avatars can reflect it too —
  // see Shell.tsx's profile-sync effect and ProfilePage.tsx's handlePhotoChange.
  photoDataUrl?: string | null;
}

interface AuthState {
  token: string | null;
  user: AuthUser | null;
  // Refetched once per token by Shell's permission-sync effect (same "once per mount, patch the
  // store" pattern as the profile-sync effect) — never set directly from a login response, which
  // carries no permissions field. Persisted so a reload shows the last-known matrix instantly
  // rather than briefly denying every gated page while the fresh fetch is in flight; the fresh
  // fetch that follows corrects any staleness, same tradeoff `user.role` already makes.
  permissions: MyPermissionsResponse | null;
  setAuth: (token: string, user: AuthUser) => void;
  setPermissions: (permissions: MyPermissionsResponse) => void;
  clearAuth: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      user: null,
      permissions: null,
      setAuth: (token, user) => set({ token, user }),
      setPermissions: (permissions) => set({ permissions }),
      clearAuth: () => set({ token: null, user: null, permissions: null }),
    }),
    { name: 'onehr-auth', storage: createJSONStorage(() => sessionStorage) }
  )
);
