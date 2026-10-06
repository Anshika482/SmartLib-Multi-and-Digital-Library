import { api } from './apiClient';
import type { BookSummary, Page } from '@/types/api';

/**
 * Reading the catalogue.
 *
 * <p>The query is built here rather than at the call site so the backend's
 * rules live in one place: page counts from zero, size is capped at 50, and
 * sortBy must be one of the fields BookService whitelists - anything else is
 * a 400 rather than a silent default.</p>
 */

/** The sort keys BookService accepts. Not a guess: they are its whitelist. */
export type BookSortBy = 'id' | 'title' | 'author' | 'isbn' | 'totalCopies' | 'availableCopies';

export interface BookQuery {
  page?: number;
  size?: number;
  sortBy?: BookSortBy;
  direction?: 'asc' | 'desc';
  keyword?: string;
  categoryId?: number;
}

export function bookQueryString(query: BookQuery = {}): string {
  const params = new URLSearchParams();

  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 10));
  params.set('sortBy', query.sortBy ?? 'id');
  params.set('direction', query.direction ?? 'asc');

  // Only sent when there is one: an empty keyword is a different query from no
  // keyword, and the backend treats it as such.
  if (query.keyword !== undefined && query.keyword.trim().length > 0) {
    params.set('keyword', query.keyword.trim());
  }
  if (query.categoryId !== undefined) {
    params.set('categoryId', String(query.categoryId));
  }

  return params.toString();
}

export const catalogueService = {
  list(query: BookQuery = {}, signal?: AbortSignal): Promise<Page<BookSummary>> {
    return api.get<Page<BookSummary>>(`/api/books?${bookQueryString(query)}`, { signal });
  },

  /**
   * One book.
   *
   * <p>A book in another library answers 404, the same as an id that never
   * existed - the backend does not distinguish them, and neither should
   * anything built on it.</p>
   */
  get(bookId: number, signal?: AbortSignal): Promise<BookSummary> {
    return api.get<BookSummary>(`/api/books/${bookId}`, { signal });
  },
};
