import { useSearchParams } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { ClipboardList, Search } from 'lucide-react';
import { BookCover } from '@/components/book';
import { EmptyState, QueryState } from '@/components/states';
import { DataTable, Pagination } from '@/components/table';
import { PageHeader, Segmented } from '@/components/ui';
import { Input } from '@/components/form';
import { StatusBadge } from '@/components/status';
import { date, dateTime } from '@/lib/format';
import { RequestActions } from './RequestActions';
import { useStaff } from './useStaff';
import { useDebounced } from './useDebounced';

type Status = 'PENDING' | 'APPROVED' | 'CLOSED' | 'ALL';

export default function RequestsPage() {
  const { api, libraryId } = useStaff();
  const [params, setParams] = useSearchParams();
  const status = (params.get('status') as Status) || 'PENDING';
  const page = Math.max(0, Number(params.get('page') ?? 1) - 1);
  const [q, setQ, debounced] = useDebounced(params.get('q') ?? '');
  const query = useQuery({
    queryKey: ['manage', libraryId, 'requests', status, debounced, page],
    queryFn: () => api.requests({ status, q: debounced || undefined, page, size: 15 }), placeholderData: keepPreviousData,
  });
  return (
    <>
      <PageHeader title="Borrow requests" subtitle="Requests made to your library. Approving reserves a copy; issuing creates the loan." icon={<ClipboardList className="size-5" />} />
      <div className="mb-5 flex flex-wrap items-center gap-3">
        <Segmented label="Request status" value={status} onChange={(v) => setParams({ status: v })}
          options={[{ value: 'PENDING', label: 'Pending' }, { value: 'APPROVED', label: 'Ready for pickup' }, { value: 'CLOSED', label: 'Closed' }, { value: 'ALL', label: 'All' }]} />
        <div className="w-full max-w-xs"><label htmlFor="rq-search" className="sr-only">Search requests</label>
          <Input id="rq-search" leading={<Search className="size-4" />} placeholder="Member or book…" value={q} onChange={(e) => setQ(e.target.value)} /></div>
      </div>
      <div className="glass p-4">
        <QueryState query={query} isEmpty={(d) => d.content.length === 0} empty={<EmptyState title={status === 'PENDING' ? 'No pending requests' : 'No requests found'} />}>
          {(d) => (
            <>
              <DataTable caption="Borrow requests" rows={d.content} rowKey={(r) => r.id} columns={[
                { key: 'book', header: 'Book', primary: true, cell: (r) => (
                  <div className="flex items-center gap-3"><BookCover book={r.book} className="w-9 shrink-0" rounded="rounded" />
                    <div className="min-w-0"><p className="line-clamp-1 font-medium text-fg">{r.book.title}</p><p className="text-xs text-faint">{r.book.authors[0]}</p></div></div>) },
                { key: 'member', header: 'Member', cell: (r) => <div><p className="text-fg">{r.member?.fullName}</p><p className="text-xs text-faint">{r.member?.email}</p></div> },
                { key: 'date', header: 'Requested', sortValue: (r) => r.createdAt, cell: (r) => <span className="text-xs">{dateTime(r.createdAt)}</span> },
                { key: 'copies', header: 'Available', sortValue: (r) => r.availableCopies ?? 0, cell: (r) => <span className={r.availableCopies ? 'text-success' : 'text-danger'}>{r.availableCopies ?? 0}</span> },
                { key: 'status', header: 'Status', cell: (r) => <div><StatusBadge status={r.status} />{r.status === 'APPROVED' && r.pickupDeadline && <p className="mt-1 text-[11px] text-faint">Copy {r.assignedCopy} · by {date(r.pickupDeadline)}</p>}{r.memberNote && <p className="mt-1 text-[11px] text-faint">“{r.memberNote}”</p>}</div> },
                { key: 'actions', header: 'Actions', align: 'right', cell: (r) => <RequestActions request={r} /> },
              ]} />
              <Pagination page={d.page} totalPages={d.totalPages} size={d.size} totalElements={d.totalElements} label="requests" onChange={(p) => setParams({ status, page: String(p + 1) })} />
            </>
          )}
        </QueryState>
      </div>
    </>
  );
}
