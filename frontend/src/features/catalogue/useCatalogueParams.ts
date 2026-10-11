import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router';

export interface CatalogueFilters {
  q: string;
  library: number | null;
  category: string | null;
  language: string | null;
  available: boolean;
  sort: string;
  page: number; // 0-based internally, 1-based in the URL
}

/** The URL is the single source of truth for catalogue state (deep links, back/forward and refresh all work). */
export function useCatalogueParams() {
  const [params, setParams] = useSearchParams();
  const filters: CatalogueFilters = useMemo(() => ({
    q: params.get('q') ?? '',
    library: params.get('library') ? Number(params.get('library')) || null : null,
    category: params.get('category'),
    language: params.get('language'),
    available: params.get('available') === '1',
    sort: params.get('sort') ?? '',
    page: Math.max(0, (Number(params.get('page')) || 1) - 1),
  }), [params]);

  /** Any filter change resets to page 1; typing in the search box replaces history instead of pushing. */
  const update = useCallback((patch: Partial<CatalogueFilters>, opts: { replace?: boolean } = {}) => {
    setParams((prev) => {
      const next = new URLSearchParams(prev);
      const set = (k: string, v: string | null | undefined) => (v ? next.set(k, v) : next.delete(k));
      if ('q' in patch) set('q', patch.q?.trim() ? patch.q : null);
      if ('library' in patch) set('library', patch.library ? String(patch.library) : null);
      if ('category' in patch) set('category', patch.category ?? null);
      if ('language' in patch) set('language', patch.language ?? null);
      if ('available' in patch) set('available', patch.available ? '1' : null);
      if ('sort' in patch) set('sort', patch.sort || null);
      if ('page' in patch) set('page', patch.page && patch.page > 0 ? String(patch.page + 1) : null);
      else next.delete('page');
      return next;
    }, { replace: opts.replace });
  }, [setParams]);

  const clearAll = useCallback(() => setParams(new URLSearchParams()), [setParams]);
  return { filters, update, clearAll };
}
