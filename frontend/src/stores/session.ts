import { create } from 'zustand';
import type { Me } from '@/api/types';

export type SessionStatus = 'unknown' | 'authenticated' | 'anonymous';

interface SessionState {
  status: SessionStatus;
  accessToken: string | null;
  user: Me | null;
  setSession: (token: string, user: Me) => void;
  setUser: (user: Me) => void;
  clear: () => void;
}

/** The access token lives only in memory (never localStorage); the refresh token is an httpOnly cookie. */
export const useSession = create<SessionState>((set) => ({
  status: 'unknown',
  accessToken: null,
  user: null,
  setSession: (accessToken, user) => set({ status: 'authenticated', accessToken, user }),
  setUser: (user) => set({ user }),
  clear: () => set({ status: 'anonymous', accessToken: null, user: null }),
}));

export function roleHome(role: Me['role'] | undefined) {
  switch (role) {
    case 'LIBRARIAN':
      return '/app/librarian';
    case 'ADMIN':
      return '/app/admin';
    case 'SUPER_ADMIN':
      return '/app/super-admin';
    default:
      return '/app/member';
  }
}
