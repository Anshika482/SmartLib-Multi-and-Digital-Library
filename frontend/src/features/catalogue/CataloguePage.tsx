import { useEffect, useRef, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Filter, Library, Search, SlidersHorizontal, X } from 'lucide-react';
import { publicApi } from '@/api/endpoints';
import { BookCard, BookCardSkeleton } from '@/components/book';
import { EmptyState, ErrorState } from '@/components/states';
import { Pagination } from '@/components/table';
import { Button } from '@/components/ui';
import { Input, Select } from '@/components/form';
import { cn } from '@/lib/cn';
import { useCatalogueParams } from './useCatalogueParams';

const PAGE_SIZE = 12;

export default function CataloguePage() {
  const { filters, update, clearAll } = useCatalogueParams();
  const [text, setText] = useState(filters.q);
  const [showFilters, setShowFilters] = useState(false);
  const typing = useRef(false);

  // keep the input in sync when the URL changes (back/forward, chips, clear)
  useEffect(() => {
    if (!typing.current) setText(filters.q);
  }, [filters.q]);

  useEffect(() => {
    if (!typing.current) return;
    const t = setTimeout(() => {
      typing.current = false;
      if (text.trim() !== filters.q.trim()) update({ q: text }, { replace: true });
    }, 300);
    return () => clearTimeout(t);
  }, [text, filters.q, update]);

  const libraries = useQuery({ queryKey: ['public', 'libraries', ''], queryFn: () => publicApi.libraries() });
  const categories = useQuery({ queryKey: ['public', 'categories'], queryFn: publicApi.categories });
  const languages = useQuery({ queryKey: ['public', 'languages'], queryFn: publicApi.languages });
  const sort = filters.sort || (filters.q ? 'relevance' : 'title');
  const query = useQuery({
    queryKey: ['catalogue', filters],
    queryFn: ({ signal }) => publicApi.catalogue({
      q: filters.q || undefined, libraryId: filters.library, category: filters.category, language: filters.language,
      available: filters.available, sort: sort === 'relevance' ? undefined : sort, page: filters.page, size: PAGE_SIZE,
    }, signal),
    placeholderData: keepPreviousData,
  });

  const libraryName = libraries.data?.find((l) => l.id === filters.library)?.name;
  const categoryName = categories.data?.find((c) => c.slug === filters.category)?.name;
  const hasFilters = !!(filters.q || filters.library || filters.category || filters.language || filters.available);

  const filterPanel = (
    <div className="space-y-5">
      <div>
        <label htmlFor="f-library" className="mb-1.5 flex items-center gap-2 text-sm font-semibold"><Library className="size-4 text-accent-2" />Library</label>
        <Select id="f-library" value={filters.library ?? ''} onChange={(e) => update({ library: e.target.value ? Number(e.target.value) : null })}>
          <option value="">All Libraries</option>
          {libraries.data?.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
        </Select>
      </div>
      <fieldset>
        <legend className="mb-1.5 text-sm font-semibold">Categories</legend>
        <div className="flex flex-col gap-0.5">
          <button type="button" onClick={() => update({ category: null })} aria-pressed={!filters.category}
            className={cn('rounded-lg px-2.5 py-1.5 text-left text-sm transition', !filters.category ? 'bg-accent/15 text-fg' : 'text-muted hover:text-fg')}>All categories</button>
          {categories.data?.map((c) => (
            <button key={c.id} type="button" onClick={() => update({ category: c.slug })} aria-pressed={filters.category === c.slug}
              className={cn('flex items-center justify-between rounded-lg px-2.5 py-1.5 text-left text-sm transition', filters.category === c.slug ? 'bg-accent/15 text-fg' : 'text-muted hover:text-fg')}>
              {c.name}<span className="text-xs text-faint">{c.bookCount}</span>
            </button>
          ))}
        </div>
      </fieldset>
      {!!languages.data?.length && (
        <div>
          <label htmlFor="f-language" className="mb-1.5 block text-sm font-semibold">Language</label>
          <Select id="f-language" value={filters.language ?? ''} onChange={(e) => update({ language: e.target.value || null })}>
            <option value="">Any language</option>
            {languages.data.map((l) => <option key={l} value={l}>{l}</option>)}
          </Select>
        </div>
      )}
      <label className="flex cursor-pointer items-center justify-between gap-3 rounded-xl border border-line bg-ink/40 px-3 py-2.5 text-sm">
        Available now only
        <input type="checkbox" className="size-4 accent-[#1688ff]" checked={filters.available} onChange={(e) => update({ available: e.target.checked })} />
      </label>
      {hasFilters && <Button variant="outline" className="w-full" onClick={() => { typing.current = false; setText(''); clearAll(); }}>Clear all filters</Button>}
    </div>
  );

  const data = query.data;
  return (
    <div className="mx-auto max-w-7xl px-4 py-8 sm:px-6">
      <div className="glass relative mb-6 overflow-hidden p-6 sm:p-8">
        <div className="pointer-events-none absolute -right-10 -top-16 size-72 rounded-full bg-accent/20 blur-3xl" aria-hidden />
        <p className="eyebrow">Public catalogue</p>
        <h1 className="mt-1 text-3xl font-bold tracking-tight">Explore books from <span className="text-gradient">All Libraries</span></h1>
        <p className="mt-2 max-w-2xl text-sm text-muted">Search, discover and find your next great read across every SmartLib library. Availability is shown separately for each library.</p>
        <form role="search" className="mt-5 flex gap-2" onSubmit={(e) => { e.preventDefault(); typing.current = false; update({ q: text }); }}>
          <label htmlFor="catalogue-search" className="sr-only">Search by title, author or ISBN</label>
          <Input id="catalogue-search" leading={<Search className="size-4" />} value={text} placeholder="Search by title, author or ISBN…"
            onChange={(e) => { typing.current = true; setText(e.target.value); }} className="h-12" />
          {text && <button type="button" className="btn btn-ghost btn-icon h-12 w-12" aria-label="Clear search" onClick={() => { typing.current = false; setText(''); update({ q: '' }); }}><X className="size-4" /></button>}
          <Button type="submit" className="h-12 px-6">Search</Button>
        </form>
      </div>

      <div className="grid gap-6 lg:grid-cols-[250px_1fr]">
        <aside className="hidden lg:block">
          <div className="glass sticky top-24 p-5">
            <h2 className="mb-4 flex items-center gap-2 font-semibold"><SlidersHorizontal className="size-4 text-accent-2" />Filter books</h2>
            {filterPanel}
          </div>
        </aside>
        <div className="min-w-0">
          <div className="mb-4 flex flex-wrap items-center gap-2">
            <Button variant="outline" size="sm" className="lg:hidden" icon={<Filter className="size-4" />} onClick={() => setShowFilters((s) => !s)} aria-expanded={showFilters}>Filters</Button>
            <p className="text-sm text-muted" aria-live="polite">
              {!data ? 'Loading…' : query.isPlaceholderData ? 'Updating results…' : <>{data.totalElements === 0 ? 'No results' : <><b className="text-fg">{data.totalElements}</b> {data.totalElements === 1 ? 'book' : 'books'}</>}
                {filters.q && <> for “<b className="text-fg">{filters.q}</b>”</>} in <b className="text-fg">{libraryName ?? 'all libraries'}</b></>}
            </p>
            <div className="ml-auto flex items-center gap-2">
              <label htmlFor="sort" className="text-xs text-faint">Sort</label>
              <Select id="sort" value={sort} onChange={(e) => update({ sort: e.target.value === 'relevance' ? '' : e.target.value })} className="h-9 w-auto py-0 text-sm">
                {filters.q && <option value="relevance">Relevance</option>}
                <option value="title">Title A–Z</option>
                <option value="newest">Newly added</option>
                <option value="popular">Most borrowed</option>
                <option value="year">Publication year</option>
              </Select>
            </div>
          </div>
          {showFilters && <div className="glass mb-4 p-4 lg:hidden">{filterPanel}</div>}
          {hasFilters && (
            <div className="mb-4 flex flex-wrap gap-2" aria-label="Active filters">
              {filters.q && <FilterChip label={`Search: ${filters.q}`} onRemove={() => { typing.current = false; setText(''); update({ q: '' }); }} />}
              {filters.library && <FilterChip label={`Library: ${libraryName ?? filters.library}`} onRemove={() => update({ library: null })} />}
              {filters.category && <FilterChip label={`Category: ${categoryName ?? filters.category}`} onRemove={() => update({ category: null })} />}
              {filters.language && <FilterChip label={`Language: ${filters.language}`} onRemove={() => update({ language: null })} />}
              {filters.available && <FilterChip label="Available now" onRemove={() => update({ available: false })} />}
            </div>
          )}

          {query.isError && !data ? <ErrorState error={query.error} onRetry={() => query.refetch()} /> : !data ? (
            <div className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-4">{Array.from({ length: 8 }, (_, i) => <BookCardSkeleton key={i} />)}</div>
          ) : data.content.length === 0 ? (
            <div className="glass">
              <EmptyState icon={<Search className="size-6" />} title={filters.q ? <>No books found for “{filters.q}”.</> : 'No books match these filters.'}
                description={<div className="text-left"><p>Try another:</p><ul className="mt-1 list-inside list-disc"><li>title</li><li>author</li><li>ISBN</li><li>library</li></ul></div>}
                action={<>
                  {filters.q && <Button variant="outline" onClick={() => { typing.current = false; setText(''); update({ q: '' }); }}>Clear search</Button>}
                  {filters.library && <Button variant="outline" onClick={() => update({ library: null })}>Search all libraries</Button>}
                  <Button onClick={() => { typing.current = false; setText(''); clearAll(); }}>Show all books</Button>
                </>} />
            </div>
          ) : (
            <div className={cn('transition-opacity', query.isPlaceholderData && 'opacity-60')} aria-busy={query.isFetching}>
              <div className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-4">
                {data.content.map((b) => <BookCard key={b.id} book={b} libraryId={filters.library} />)}
              </div>
              <Pagination page={data.page} totalPages={data.totalPages} size={data.size} totalElements={data.totalElements} label="books"
                onChange={(p) => { update({ ...filters, page: p }); window.scrollTo({ top: 0, behavior: 'smooth' }); }} />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function FilterChip({ label, onRemove }: { label: string; onRemove: () => void }) {
  return (
    <span className="chip !border-accent/40 !bg-accent/10 !text-fg">
      {label}
      <button type="button" onClick={onRemove} aria-label={`Remove filter ${label}`} className="-mr-1 rounded-full p-0.5 hover:bg-white/10"><X className="size-3" /></button>
    </span>
  );
}
