import { api } from './apiClient';
import type { Report } from '@/types/api';

/**
 * What a library, or the whole deployment, actually did.
 *
 * <p>The dates are the only thing sent. There is no library parameter and none
 * accepted: the server decides the scope from the caller's role, so a
 * librarian's report covers their library and a super administrator's covers
 * every library, and nothing here can change which.</p>
 */
export const reportService = {
  /**
   * One report over a range of days, both ends included.
   *
   * <p>An inverted or over-long range is refused by the server with a message
   * saying which - it is not answered with a page of zeroes, which would read
   * as a library that did nothing.</p>
   */
  load(from: string, to: string, signal?: AbortSignal): Promise<Report> {
    const params = new URLSearchParams({ from, to });
    return api.get<Report>(`/api/reports?${params.toString()}`, { signal });
  },
};
