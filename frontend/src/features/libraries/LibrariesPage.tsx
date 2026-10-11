import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Building2, Search } from 'lucide-react';
import { publicApi } from '@/api/endpoints';
import { LibraryCard } from '@/components/book';
import { EmptyState, ErrorState } from '@/components/states';
import { Input } from '@/components/form';
import { Skeleton } from '@/components/ui';

export default function LibrariesPage() {
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const [text, setText] = useState(q);
  useEffect(() => {
    const t = setTimeout(() => {
      if (text.trim() !== q) setParams(text.trim() ? { q: text.trim() } : {}, { replace: true });
    }, 300);
    return () => clearTimeout(t);
  }, [text, q, setParams]);
  const libraries = useQuery({ queryKey: ['public', 'libraries', q], queryFn: () => publicApi.libraries(q || undefined), placeholderData: keepPreviousData });
  return (
    <div className="mx-auto max-w-7xl px-4 py-8 sm:px-6">
      <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="eyebrow">Libraries directory</p>
          <h1 className="mt-1 text-3xl font-bold tracking-tight">Our Libraries</h1>
          <p className="mt-1 text-sm text-muted">Discover and explore our partner libraries — then browse each one's books.</p>
        </div>
        <div className="w-full max-w-sm">
          <label htmlFor="lib-search" className="sr-only">Search libraries</label>
          <Input id="lib-search" leading={<Search className="size-4" />} placeholder="Search by name or city…" value={text} onChange={(e) => setText(e.target.value)} />
        </div>
      </div>
      {libraries.isError ? <ErrorState error={libraries.error} onRetry={() => libraries.refetch()} /> : libraries.isPending ? (
        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-80" />)}</div>
      ) : libraries.data.length === 0 ? (
        <div className="glass"><EmptyState icon={<Building2 className="size-6" />} title={q ? `No libraries match “${q}”` : 'No libraries yet'} /></div>
      ) : (
        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">{libraries.data.map((l) => <LibraryCard key={l.id} library={l} />)}</div>
      )}
    </div>
  );
}
