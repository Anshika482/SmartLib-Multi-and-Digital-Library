import { api } from './apiClient';
import type { RegistrationRequest, RegistrationResponse } from '@/types/api';

/**
 * Registering.
 *
 * <p>One public endpoint, anonymous by construction: a visitor has no token,
 * and a signed-in person has no reason to register. Sending one would only
 * mean a stale token dragging a refresh into a call that never needed it.</p>
 *
 * <p>Nothing here names a role. The request carries a registration type, and
 * the backend decides what that is worth - so a modified client cannot ask for
 * an authority it was not given.</p>
 */
export const registrationService = {
  register(request: RegistrationRequest, signal?: AbortSignal): Promise<RegistrationResponse> {
    return api.post<RegistrationResponse>('/api/auth/register', request, { signal, anonymous: true });
  },
};

/** The backend's own limits, mirrored so a doomed request can be stopped early. */
export const PASSWORD_MIN_LENGTH = 8;
export const PASSWORD_MAX_LENGTH = 72;
export const USERNAME_MIN_LENGTH = 3;
