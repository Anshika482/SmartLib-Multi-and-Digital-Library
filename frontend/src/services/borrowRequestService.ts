import { api } from './apiClient';
import type { BorrowRequest, BorrowRequestStatus, Page, Transaction } from '@/types/api';

/**
 * Asking for a book, and what the desk does about it.
 *
 * <p>Every call here is scoped by the backend to the caller's own library, and
 * a member's calls are scoped again to their own requests. This client neither
 * widens that nor relies on being able to: there is no library parameter to
 * send and no member id in any signature.</p>
 *
 * <p>The staff calls are declared here beside the member ones because they are
 * the same resource. Which of them a caller may actually make is decided by the
 * API, not by which function a screen imports.</p>
 */

/** The sort keys BorrowRequestService accepts. Its whitelist, not a guess. */
export type RequestSortBy = 'id' | 'requestedAt' | 'status';

export interface RequestQuery {
  page?: number;
  size?: number;
  sortBy?: RequestSortBy;
  direction?: 'asc' | 'desc';
}

function queryString(query: RequestQuery, extra: Record<string, string> = {}): string {
  const params = new URLSearchParams({
    page: String(query.page ?? 0),
    size: String(query.size ?? 10),
    sortBy: query.sortBy ?? 'requestedAt',
    direction: query.direction ?? 'desc',
    ...extra,
  });

  return params.toString();
}

export const borrowRequestService = {
  /** A member asks for a book. The borrower is the token's owner, never a parameter. */
  request(bookId: number, signal?: AbortSignal): Promise<BorrowRequest> {
    return api.post<BorrowRequest>('/api/borrow-requests', { bookId }, { signal });
  },

  /** The caller's own requests. */
  mine(query: RequestQuery = {}, signal?: AbortSignal): Promise<Page<BorrowRequest>> {
    return api.get<Page<BorrowRequest>>(`/api/borrow-requests/mine?${queryString(query)}`, { signal });
  },

  /** A member withdraws their own request. */
  cancel(id: number, signal?: AbortSignal): Promise<BorrowRequest> {
    return api.post<BorrowRequest>(`/api/borrow-requests/${id}/cancel`, {}, { signal });
  },

  /** The library's queue, in one state. Staff only, as the API enforces. */
  queue(status: BorrowRequestStatus, query: RequestQuery = {}, signal?: AbortSignal): Promise<Page<BorrowRequest>> {
    return api.get<Page<BorrowRequest>>(
      `/api/borrow-requests?${queryString({ direction: 'asc', ...query }, { status })}`,
      { signal },
    );
  },

  approve(id: number, signal?: AbortSignal): Promise<BorrowRequest> {
    return api.post<BorrowRequest>(`/api/borrow-requests/${id}/approve`, {}, { signal });
  },

  reject(id: number, signal?: AbortSignal): Promise<BorrowRequest> {
    return api.post<BorrowRequest>(`/api/borrow-requests/${id}/reject`, {}, { signal });
  },

  /**
   * The copy is handed over.
   *
   * <p>Answers with the loan rather than the request: from here the transaction
   * is what matters, and it is what a return will need.</p>
   */
  issue(id: number, dueDate: string, signal?: AbortSignal): Promise<Transaction> {
    return api.post<Transaction>(`/api/borrow-requests/${id}/issue`, { dueDate }, { signal });
  },
};
