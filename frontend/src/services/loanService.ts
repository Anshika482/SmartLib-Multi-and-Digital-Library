import { api } from './apiClient';
import type { Page, Transaction, TransactionStatus } from '@/types/api';

/**
 * Loans, and what is owed on them.
 *
 * <p>Every call is scoped by the backend to the caller's own library, and a
 * member's calls to their own loans. This client neither widens that nor relies
 * on being able to - there is no library parameter to send.</p>
 *
 * <p><b>Nothing here computes a fine or decides what is overdue.</b> Both come
 * off the response, worked out by the one policy on the server. A screen that
 * did its own arithmetic would be a second implementation of a rule that is
 * meant to exist once.</p>
 */

/** The sort keys TransactionService accepts. Its whitelist, not a guess. */
export type LoanSortBy = 'id' | 'issueDate' | 'dueDate' | 'returnDate' | 'status';

export interface LoanQuery {
  page?: number;
  size?: number;
  sortBy?: LoanSortBy;
  direction?: 'asc' | 'desc';
}

function queryString(query: LoanQuery, fallbackSort: LoanSortBy): string {
  return new URLSearchParams({
    page: String(query.page ?? 0),
    size: String(query.size ?? 20),
    sortBy: query.sortBy ?? fallbackSort,
    direction: query.direction ?? 'desc',
  }).toString();
}

export const loanService = {
  /**
   * One account's loans.
   *
   * <p>The id is the caller's own, read from their profile. A member asking for
   * anybody else's is refused by the API, which compares the id against the
   * account the token names.</p>
   */
  forUser(userId: number, query: LoanQuery = {}, signal?: AbortSignal): Promise<Page<Transaction>> {
    return api.get<Page<Transaction>>(
      `/api/transactions/user/${userId}?${queryString(query, 'issueDate')}`,
      { signal },
    );
  },

  /**
   * Loans with a fine still to settle.
   *
   * <p>No id in the path and none accepted: staff get their library's, a member
   * gets their own, and the server decides which from the token.</p>
   */
  fines(query: LoanQuery = {}, signal?: AbortSignal): Promise<Page<Transaction>> {
    return api.get<Page<Transaction>>(
      `/api/transactions/fines?${queryString({ direction: 'asc', ...query }, 'dueDate')}`,
      { signal },
    );
  },

  /** One library's loans in a given state. Staff only, as the API enforces. */
  byStatus(
    status: TransactionStatus,
    query: LoanQuery = {},
    signal?: AbortSignal,
  ): Promise<Page<Transaction>> {
    return api.get<Page<Transaction>>(
      `/api/transactions/status/${status}?${queryString({ direction: 'asc', ...query }, 'dueDate')}`,
      { signal },
    );
  },
};
