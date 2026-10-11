import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ClipboardList } from 'lucide-react';
import { meApi } from '@/api/endpoints';
import type { RequestView } from '@/api/types';
import { BookCover } from '@/components/book';
import { ConfirmDialog } from '@/components/modal';
import { EmptyState, QueryState } from '@/components/states';
import { Pagination } from '@/components/table';
import { Button, LinkButton, PageHeader, Segmented } from '@/components/ui';
import { StatusBadge } from '@/components/status';
import { date, dateTime } from '@/lib/format';

type Filter = 'ACTIVE' | 'ALL' | 'CLOSED';

export default function MyRequestsPage() {
  const [params, setParams] = useSearchParams();
  const filter = (params.get('status') as Filter) || 'ACTIVE';
  const page = Math.max(0, Number(params.get('page') ?? 1) - 1);
  const qc = useQueryClient();
  const query = useQuery({ queryKey: ['me', 'requests', filter, page], queryFn: () => meApi.requests({ status: filter, page, size: 10 }), placeholderData: keepPreviousData });
  const [cancelling, setCancelling] = useState<RequestView | null>(null);
  const cancel = useMutation({ mutationFn: (id: number) => meApi.cancelRequest(id), onSuccess: () => { toast.success('Request cancelled'); qc.invalidateQueries({ queryKey: ['me'] }); } });
  return (
    <>
      <PageHeader title="My Requests" subtitle="Track every borrow request and the library it was sent to." icon={<ClipboardList className="size-5" />}
        actions={<LinkButton to="/catalogue">Request another book</LinkButton>} />
      <Segmented label="Filter requests" value={filter} onChange={(v) => setParams({ status: v })} className="mb-5"
        options={[{ value: 'ACTIVE', label: 'Active' }, { value: 'CLOSED', label: 'Closed' }, { value: 'ALL', label: 'All' }]} />
      <QueryState query={query} isEmpty={(d) => d.content.length === 0}
        empty={<div className="glass"><EmptyState title={filter === 'ACTIVE' ? 'No active requests' : 'No requests yet'} description="Find a book, choose a library and send a request." action={<LinkButton to="/catalogue">Browse catalogue</LinkButton>} /></div>}>
        {(d) => (
          <>
            <ul className="space-y-3">
              {d.content.map((r) => (
                <li key={r.id} className="glass flex flex-col gap-4 p-4 sm:flex-row sm:items-center">
                  <BookCover book={r.book} className="w-16 shrink-0" />
                  <div className="min-w-0 flex-1">
                    <Link to={`/catalogue/${r.book.id}`} className="font-semibold hover:text-accent-2">{r.book.title}</Link>
                    <p className="text-sm text-muted">{r.library.name}</p>
                    <div className="mt-1.5 flex flex-wrap gap-x-4 gap-y-1 text-xs text-faint">
                      <span>Requested {dateTime(r.createdAt)}</span>
                      {r.pickupDeadline && r.status === 'APPROVED' && <span className="font-semibold text-accent-2">Collect by {date(r.pickupDeadline)}</span>}
                      {r.assignedCopy && r.status === 'APPROVED' && <span>Copy {r.assignedCopy}</span>}
                      {r.decisionReason && <span>Reason: {r.decisionReason}</span>}
                    </div>
                  </div>
                  <div className="flex items-center gap-2 sm:flex-col sm:items-end">
                    <StatusBadge status={r.status} />
                    {(r.status === 'PENDING' || r.status === 'APPROVED') && <Button size="sm" variant="soft-danger" onClick={() => setCancelling(r)}>Cancel</Button>}
                    {r.status === 'FULFILLED' && <Link to="/app/member/borrowings" className="text-xs font-semibold text-accent-2">View loan →</Link>}
                  </div>
                </li>
              ))}
            </ul>
            <Pagination page={d.page} totalPages={d.totalPages} size={d.size} totalElements={d.totalElements} label="requests"
              onChange={(p) => setParams({ status: filter, page: String(p + 1) })} />
          </>
        )}
      </QueryState>
      <ConfirmDialog open={!!cancelling} onOpenChange={(o) => !o && setCancelling(null)} title="Cancel this request?" tone="danger" confirmLabel="Cancel request"
        description={cancelling ? `“${cancelling.book.title}” at ${cancelling.library.name}. ${cancelling.status === 'APPROVED' ? 'The reserved copy will be released.' : ''}` : undefined}
        onConfirm={() => cancel.mutateAsync(cancelling!.id)} />
    </>
  );
}
