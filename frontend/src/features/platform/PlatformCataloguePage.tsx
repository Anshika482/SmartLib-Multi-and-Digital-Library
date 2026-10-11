import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Globe2, Search } from 'lucide-react';
import { platformApi, publicApi } from '@/api/endpoints';
import { BookCard, BookCardSkeleton } from '@/components/book';
import { BarsChart } from '@/components/charts';
import { EmptyState, QueryState } from '@/components/states';
import { Pagination } from '@/components/table';
import { PageHeader, Panel } from '@/components/ui';
import { Input, Select } from '@/components/form';
import { useDebounced } from '@/features/staff/useDebounced';

export default function PlatformCataloguePage() {
  const [q, setQ, debounced] = useDebounced('');
  const [library, setLibrary] = useState('');
  const [page, setPage] = useState(0);
  const libraries = useQuery({ queryKey: ['public', 'libraries', ''], queryFn: () => publicApi.libraries() });
  const categories = useQuery({ queryKey: ['platform', 'categories'], queryFn: platformApi.categories });
  const books = useQuery({ queryKey: ['catalogue', 'platform', debounced, library, page],
    queryFn: ({ signal }) => publicApi.catalogue({ q: debounced || undefined, libraryId: library ? Number(library) : null, page, size: 12 }, signal), placeholderData: keepPreviousData });
  return (
    <>
      <PageHeader title="Global catalogue" subtitle="All titles across active libraries, with per-library availability." icon={<Globe2 className="size-5" />} />
      <Panel title="Titles and loans by category" className="mb-6">
        <QueryState query={categories}>{(d) => <BarsChart data={d} xKey="category" label="Titles and 90-day loans per category" bars={[{ key: 'titles', name: 'Titles', color: '#1688ff' }, { key: 'loans90d', name: 'Loans (90 days)', color: '#39c5ff' }]} />}</QueryState>
      </Panel>
      <div className="mb-5 grid gap-3 sm:grid-cols-[1fr_260px]">
        <Input aria-label="Search the catalogue" leading={<Search className="size-4" />} placeholder="Title, author or ISBN…" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />
        <Select aria-label="Library" value={library} onChange={(e) => { setLibrary(e.target.value); setPage(0); }}>
          <option value="">All libraries</option>{libraries.data?.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
        </Select>
      </div>
      <QueryState query={books} loading={<div className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-4">{Array.from({ length: 8 }, (_, i) => <BookCardSkeleton key={i} />)}</div>}
        isEmpty={(d) => d.content.length === 0} empty={<div className="glass"><EmptyState title="No books found" /></div>}>
        {(d) => (
          <>
            <div className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-4">{d.content.map((b) => <BookCard key={b.id} book={b} />)}</div>
            <Pagination page={d.page} totalPages={d.totalPages} size={d.size} totalElements={d.totalElements} onChange={setPage} label="titles" />
          </>
        )}
      </QueryState>
    </>
  );
}
