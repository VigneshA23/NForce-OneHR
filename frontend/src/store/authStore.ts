import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';

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
