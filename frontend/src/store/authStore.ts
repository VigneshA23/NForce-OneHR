import { create } from 'zustand';
import { persist, createJSONStorage, type StateStorage } from 'zustand/middleware';

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
  // `remember` is only passed by the sign-in flow ("Remember me"): true keeps the session in
  // localStorage (survives closing the browser), false keeps it in sessionStorage (this tab only).
  // Every other caller (profile photo sync, password change) omits it and keeps the current mode.
  setAuth: (token: string, user: AuthUser, remember?: boolean) => void;
  clearAuth: () => void;
}

const STORE_NAME = 'onehr-auth';

// A remembered session lives in localStorage, a normal one in sessionStorage. Reads check
// sessionStorage first so a tab that signed in without "Remember me" is never overridden by
// another tab's remembered session.
let remembered = false;
try {
  remembered = !sessionStorage.getItem(STORE_NAME) && !!localStorage.getItem(STORE_NAME);
} catch { /* no DOM storage (tests, blocked storage) — behave as a normal session */ }

const authStorage: StateStorage = {
  getItem: (name) => sessionStorage.getItem(name) ?? localStorage.getItem(name),
  setItem: (name, value) => {
    const [target, other] = remembered ? [localStorage, sessionStorage] : [sessionStorage, localStorage];
    other.removeItem(name);
    target.setItem(name, value);
  },
  removeItem: (name) => {
    sessionStorage.removeItem(name);
    localStorage.removeItem(name);
  },
};

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      user: null,
      setAuth: (token, user, remember) => {
        if (remember !== undefined) remembered = remember; // must flip before set() — persist writes synchronously
        set({ token, user });
      },
      // Every sign-out path (menu, forced logout via 403 or SSE) goes through here, so this is
      // also what ends a remembered session.
      clearAuth: () => {
        remembered = false;
        try { localStorage.removeItem(STORE_NAME); } catch { /* storage unavailable */ }
        set({ token: null, user: null });
      },
    }),
    { name: STORE_NAME, storage: createJSONStorage(() => authStorage) }
  )
);
