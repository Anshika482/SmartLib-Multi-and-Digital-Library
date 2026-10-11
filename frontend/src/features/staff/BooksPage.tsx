import { useState } from 'react';
import { Link } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { BookCopy, Plus, Search } from 'lucide-react';
import { BookCover } from '@/components/book';
import { EmptyState, QueryState } from '@/components/states';
import { DataTable, Pagination } from '@/components/table';
import { LinkButton, PageHeader, Segmented } from '@/components/ui';
import { Input } from '@/components/form';
import { StatusBadge } from '@/components/status';
import { useStaff } from './useStaff';
import { useDebounced } from './useDebounced';

export default function BooksPage() {
  const { api, libraryId, base } = useStaff();
  const [status, setStatus] = useState<'ACTIVE' | 'WITHDRAWN' | 'ALL'>('ACTIVE');
  const [page, setPage] = useState(0);
  const [q, setQ, debounced] = useDebounced('');
  const query = useQuery({ queryKey: ['manage', libraryId, 'holdings', status, debounced, page],
    queryFn: () => api.holdings({ status, q: debounced || undefined, page, size: 20 }), placeholderData: keepPreviousData });
  return (
    <>
      <PageHeader title="Books & copies" subtitle="Your library's titles and physical copies." icon={<BookCopy className="size-5" />}
        actions={<LinkButton to={`${base}/books/new`} icon={<Plus className="size-4" />}>Add book</LinkButton>} />
      <div className="mb-5 flex flex-wrap items-center gap-3">
        <Segmented label="Status" value={status} onChange={(v) => { setStatus(v); setPage(0); }} options={[{ value: 'ACTIVE', label: 'In catalogue' }, { value: 'WITHDRAWN', label: 'Withdrawn' }, { value: 'ALL', label: 'All' }]} />
        <div className="w-full max-w-xs"><label htmlFor="bk-search" className="sr-only">Search books</label>
          <Input id="bk-search" leading={<Search className="size-4" />} placeholder="Title, author or ISBN…" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} /></div>
      </div>
      <div className="glass p-4">
        <QueryState query={query} isEmpty={(d) => d.content.length === 0}
          empty={<EmptyState title="No books found" action={<LinkButton to={`${base}/books/new`} icon={<Plus className="size-4" />}>Add a book</LinkButton>} />}>
          {(d) => (
            <>
              <DataTable caption="Library books" rows={d.content} rowKey={(h) => h.holdingId} columns={[
                { key: 'title', header: 'Title', primary: true, sortValue: (h) => h.book.title, cell: (h) => (
                  <Link to={`${base}/books/${h.holdingId}`} className="flex items-center gap-3 hover:text-accent-2">
                    <BookCover book={h.book} className="w-9 shrink-0" rounded="rounded" />
                    <span className="min-w-0"><span className="line-clamp-1 font-medium text-fg">{h.book.title}</span><span className="text-xs text-faint">{h.book.authors.join(', ')}</span></span>
                  </Link>) },
                { key: 'isbn', header: 'ISBN', cell: (h) => <span className="font-mono text-xs">{h.book.isbn13 ?? '—'}</span> },
                { key: 'shelf', header: 'Shelf', cell: (h) => h.shelfLocation ?? '—' },
                { key: 'copies', header: 'Copies', sortValue: (h) => h.total, cell: (h) => <span className="text-xs"><b className="text-fg">{h.total}</b> total · <span className="text-success">{h.available} available</span> · {h.borrowed} on loan{h.reserved ? ` · ${h.reserved} reserved` : ''}{h.unavailable ? ` · ${h.unavailable} lost/damaged` : ''}</span> },
                { key: 'pending', header: 'Requests', sortValue: (h) => h.pendingRequests, cell: (h) => h.pendingRequests || '—' },
                { key: 'status', header: 'Status', cell: (h) => <StatusBadge status={h.status === 'ACTIVE' ? 'AVAILABLE' : 'WITHDRAWN'} /> },
                { key: 'actions', header: '', align: 'right', cell: (h) => <Link to={`${base}/books/${h.holdingId}`} className="btn btn-outline btn-sm">Manage</Link> },
              ]} />
              <Pagination page={d.page} totalPages={d.totalPages} size={d.size} totalElements={d.totalElements} onChange={setPage} label="titles" />
            </>
          )}
        </QueryState>
      </div>
    </>
  );
}
