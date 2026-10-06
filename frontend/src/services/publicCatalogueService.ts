import { api } from './apiClient';
import type { Page, PublicBook, PublicLibrary } from '@/types/api';

/**
 * The catalogue as a visitor sees it.
 *
 * <p>Separate from catalogueService on purpose. That one is scoped to the
 * signed-in caller's library and carries copy counts; this one is the public
 * OPAC and carries neither. Keeping them apart means a public screen cannot
 * accidentally call the authenticated endpoint and quietly start requiring a
 * token, and an authenticated screen cannot settle for the thinner data.</p>
 */

/** The backend caps a public page at 24. Asking for more is a 400. */
export const MAX_PUBLIC_PAGE_SIZE = 24;

export interface PublicSearchQuery {
  keyword?: string;
  page?: number;
  size?: number;
}

export function publicCatalogueQuery(query: PublicSearchQuery = {}): string {
  const params = new URLSearchParams();

  params.set('page', String(query.page ?? 0));
  params.set('size', String(Math.min(query.size ?? 12, MAX_PUBLIC_PAGE_SIZE)));

  if (query.keyword !== undefined && query.keyword.trim().length > 0) {
    params.set('keyword', query.keyword.trim());
  }

  return params.toString();
}

export const publicCatalogueService = {
  search(query: PublicSearchQuery = {}, signal?: AbortSignal): Promise<Page<PublicBook>> {
    // anonymous: this endpoint takes no token, and sending one would only mean
    // a stale token triggering a refresh for a request that never needed it.
    return api.get<Page<PublicBook>>(`/api/public/catalogue?${publicCatalogueQuery(query)}`, {
      signal,
      anonymous: true,
    });
  },

  libraries(signal?: AbortSignal): Promise<PublicLibrary[]> {
    return api.get<PublicLibrary[]>('/api/public/libraries', { signal, anonymous: true });
  },
};
