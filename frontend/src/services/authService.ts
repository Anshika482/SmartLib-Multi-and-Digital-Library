import type { LoginResponse, UserProfile } from '@/types/api';
import { api } from './apiClient';
import { tokenStorage } from './tokenStorage';

/**
 * The four calls a session is made of.
 *
 * <p>Only this module knows the auth endpoints' paths and shapes; everything
 * above it deals in a profile and a promise.</p>
 */
export const authService = {
  /** Exchanges credentials for a session and stores it. */
  async login(username: string, password: string): Promise<UserProfile> {
    const tokens = await api.post<LoginResponse>('/api/auth/login', { username, password });
    tokenStorage.save(tokens);

    return authService.me();
  },

  /** Who the current token belongs to. Also how a stored session is checked on load. */
  me(): Promise<UserProfile> {
    return api.get<UserProfile>('/api/users/me');
  },

  /**
   * Ends the session at both ends.
   *
   * <p>The local tokens are cleared whatever the server says: a logout that
   * fails on the network must still sign the person out of this browser.</p>
   */
  async logout(): Promise<void> {
    const refreshToken = tokenStorage.refreshToken();

    try {
      if (refreshToken !== null) {
        await api.post<void>('/api/auth/logout', { refreshToken });
      }
    } catch {
      // Nothing to tell the person: they asked to be signed out, and they are.
    } finally {
      tokenStorage.clear();
    }
  },
};
