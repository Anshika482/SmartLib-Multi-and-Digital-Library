import { api } from './apiClient';
import type { Dashboard } from '@/types/api';

/**
 * The figures behind the overview screen.
 *
 * <p>One call for every role. The server decides which sections it contains
 * from the caller's own account - there is no role to pass and no parameter to
 * widen, so a client cannot ask for somebody else's numbers.</p>
 */
export const dashboardService = {
  load(signal?: AbortSignal): Promise<Dashboard> {
    return api.get<Dashboard>('/api/dashboard', { signal });
  },
};
