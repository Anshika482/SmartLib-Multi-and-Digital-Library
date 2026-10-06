import { api } from './apiClient';
import type { DigitalResource, Page } from '@/types/api';

/**
 * Reading what is available online.
 *
 * <p>Listing is all this does. Creating, editing and enabling a resource are
 * staff actions behind their own endpoints, and they belong with the screens
 * that perform them rather than here.</p>
 *
 * <p>What comes back is already scoped: the backend returns only the caller's
 * own library, and only enabled resources unless the caller is staff. This
 * client neither widens that nor relies on being able to.</p>
 */

/** The sort keys DigitalResourceService accepts. */
export type ResourceSortBy = 'id' | 'title' | 'resourceType' | 'createdAt' | 'updatedAt';

export interface ResourceQuery {
  page?: number;
  size?: number;
  sortBy?: ResourceSortBy;
  direction?: 'asc' | 'desc';
  bookId?: number;
}

export function resourceQueryString(query: ResourceQuery = {}): string {
  const params = new URLSearchParams();

  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 10));
  params.set('sortBy', query.sortBy ?? 'createdAt');
  params.set('direction', query.direction ?? 'desc');

  if (query.bookId !== undefined) {
    params.set('bookId', String(query.bookId));
  }

  return params.toString();
}

export const digitalResourceService = {
  list(query: ResourceQuery = {}, signal?: AbortSignal): Promise<Page<DigitalResource>> {
    return api.get<Page<DigitalResource>>(`/api/digital-resources?${resourceQueryString(query)}`, { signal });
  },

  /**
   * One resource.
   *
   * <p>A disabled resource answers 404 to a member, and so does one belonging
   * to another library - the same answer an id that never existed gets. The
   * reader treats all three identically, because the server deliberately makes
   * them indistinguishable.</p>
   */
  get(id: number, signal?: AbortSignal): Promise<DigitalResource> {
    return api.get<DigitalResource>(`/api/digital-resources/${id}`, { signal });
  },
};
