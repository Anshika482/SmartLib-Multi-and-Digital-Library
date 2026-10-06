import { api } from './apiClient';
import type { Category, Page } from '@/types/api';

/**
 * The shelves a library has.
 *
 * <p>Read here only to populate the catalogue's filter. Creating, renaming and
 * removing a category are staff actions behind their own endpoints, and they
 * belong with the screen that performs them.</p>
 *
 * <p>What comes back is the caller's own library's, decided by the backend from
 * the token. This client never asks for a library and has no way to.</p>
 */

/** The sort keys CategoryService accepts. Its whitelist, not a guess. */
export type CategorySortBy = 'id' | 'name';

/**
 * The most the backend will return in one page.
 *
 * <p>Fifty is CategoryService's ceiling, so a filter cannot be built from one
 * request beyond that. The catalogue says so on screen rather than silently
 * offering a partial list of shelves - see {@code CataloguePage}.</p>
 */
export const CATEGORY_PAGE_MAX = 50;

export const categoryService = {
  /** Every shelf that fits in one page, in the order a person would read them. */
  list(signal?: AbortSignal): Promise<Page<Category>> {
    const params = new URLSearchParams({
      page: '0',
      size: String(CATEGORY_PAGE_MAX),
      sortBy: 'name',
      direction: 'asc',
    });

    return api.get<Page<Category>>(`/api/categories?${params.toString()}`, { signal });
  },
};
