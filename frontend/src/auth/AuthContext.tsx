import { createContext } from 'react';
import type { UserProfile } from '@/types/api';

/** What every component can know about who is signed in. */
export interface AuthState {
  user: UserProfile | null;
  /** True until a stored session has been checked, so routes do not flash. */
  loading: boolean;
  signIn: (username: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
}

export const AuthContext = createContext<AuthState | null>(null);
