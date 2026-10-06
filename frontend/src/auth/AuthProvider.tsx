import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { UserProfile } from '@/types/api';
import { authService } from '@/services/authService';
import { setSessionEndedListener } from '@/services/apiClient';
import { tokenStorage } from '@/services/tokenStorage';
import { AuthContext, type AuthState } from './AuthContext';

/**
 * Holds the signed-in account for the whole application.
 *
 * <p><b>A stored token is not a session until the server agrees.</b> On load,
 * a token in storage is used to ask who it belongs to; only that answer makes
 * someone signed in. A token that has expired, been revoked, or belongs to a
 * disabled account therefore fails at the first request rather than showing a
 * shell the person cannot use.</p>
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;

    if (!tokenStorage.has()) {
      setLoading(false);
      return;
    }

    authService
      .me()
      .then((profile) => {
        if (!cancelled) {
          setUser(profile);
        }
      })
      .catch(() => {
        // Whatever was stored is not a session. Start signed out.
        tokenStorage.clear();
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, []);

  // The client ends a session when a refresh fails. This is how the interface
  // finds out, without every caller having to handle it.
  useEffect(() => {
    setSessionEndedListener(() => setUser(null));

    return () => setSessionEndedListener(null);
  }, []);

  const signIn = useCallback(async (username: string, password: string) => {
    setUser(await authService.login(username, password));
  }, []);

  const signOut = useCallback(async () => {
    await authService.logout();
    setUser(null);
  }, []);

  const value = useMemo<AuthState>(
    () => ({ user, loading, signIn, signOut }),
    [user, loading, signIn, signOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
