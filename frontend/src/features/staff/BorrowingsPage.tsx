import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Repeat2, Search, Undo2 } from 'lucide-react';
import { isApiError } from '@/api/http';
import type { LoanView } from '@/api/types';
import { BookCover } from '@/components/book';
import { Modal } from '@/components/modal';
import { EmptyState, QueryState } from '@/components/states';
import { DataTable, Pagination } from '@/components/table';
import { Alert, Button, PageHeader, Segmented } from '@/components/ui';
import { Field, Input, Textarea } from '@/components/form';
import { LoanStatusBadge } from '@/components/status';
import { date, money } from '@/lib/format';
import { cn } from '@/lib/cn';
import { useStaff } from './useStaff';
import { useDebounced } from './useDebounced';

type Filter = 'ACTIVE' | 'OVERDUE' | 'DUE_SOON' | 'HISTORY' | 'ALL';

export default function BorrowingsPage() {
  const { api, libraryId } = useStaff();
  const [params, setParams] = useSearchParams();
  const filter = (params.get('filter') as Filter) || 'ACTIVE';
  const page = Math.max(0, Number(params.get('page') ?? 1) - 1);
  const [q, setQ, debounced] = useDebounced('');
  const query = useQuery({ queryKey: ['manage', libraryId, 'borrowings', filter, debounced, page],
    queryFn: () => api.borrowings({ filter, q: debounced || undefined, page, size: 15 }), placeholderData: keepPreviousData });
  const [returning, setReturning] = useState<LoanView | null>(null);
  return (
    <>
      <PageHeader title="Borrowings & returns" subtitle="Active loans, due dates and return processing. Overdue fines are calculated automatically on return." icon={<Repeat2 className="size-5" />} />
      <div className="mb-5 flex flex-wrap items-center gap-3">
        <Segmented label="Loans" value={filter} onChange={(v) => setParams({ filter: v })}
          options={[{ value: 'ACTIVE', label: 'On loan' }, { value: 'OVERDUE', label: 'Overdue' }, { value: 'DUE_SOON', label: 'Due soon' }, { value: 'HISTORY', label: 'Returned' }, { value: 'ALL', label: 'All' }]} />
        <div className="w-full max-w-xs"><label htmlFor="ln-search" className="sr-only">Search loans</label>
          <Input id="ln-search" leading={<Search className="size-4" />} placeholder="Member, book or copy no.…" value={q} onChange={(e) => setQ(e.target.value)} /></div>
      </div>
      <div className="glass p-4">
        <QueryState query={query} isEmpty={(d) => d.content.length === 0} empty={<EmptyState title="No loans found" />}>
          {(d) => (
            <>
              <DataTable caption="Loans" rows={d.content} rowKey={(l) => l.id} columns={[
                { key: 'book', header: 'Book', primary: true, cell: (l) => (
                  <div className="flex items-center gap-3"><BookCover book={l.book} className="w-9 shrink-0" rounded="rounded" />
                    <div className="min-w-0"><p className="line-clamp-1 font-medium text-fg">{l.book.title}</p><p className="font-mono text-xs text-faint">{l.accessionNo}</p></div></div>) },
                { key: 'member', header: 'Member', cell: (l) => <div><p className="text-fg">{l.member?.fullName}</p><p className="text-xs text-faint">{l.member?.email}</p></div> },
                { key: 'issued', header: 'Issued', sortValue: (l) => l.issuedAt, cell: (l) => date(l.issuedAt) },
                { key: 'due', header: 'Due', sortValue: (l) => l.dueDate, cell: (l) => <span className={cn(l.overdue && 'font-semibold text-danger')}>{date(l.dueDate)}{l.overdue && <span className="block text-[11px]">{l.overdueDays} days late · {money(l.estimatedFinePaise)}</span>}</span> },
                { key: 'status', header: 'Status', cell: (l) => <div><LoanStatusBadge loan={l} />{l.returnedAt && <p className="mt-1 text-[11px] text-faint">Returned {date(l.returnedAt)}</p>}</div> },
                { key: 'actions', header: '', align: 'right', cell: (l) => l.status === 'ACTIVE' ? <Button size="sm" icon={<Undo2 className="size-3.5" />} onClick={() => setReturning(l)}>Process return</Button> : null },
              ]} />
              <Pagination page={d.page} totalPages={d.totalPages} size={d.size} totalElements={d.totalElements} label="loans" onChange={(p) => setParams({ filter, page: String(p + 1) })} />
            </>
          )}
        </QueryState>
      </div>
      <ReturnDialog loan={returning} onClose={() => setReturning(null)} />
    </>
  );
}

function ReturnDialog({ loan, onClose }: { loan: LoanView | null; onClose: () => void }) {
  const { api, libraryId } = useStaff();
  const qc = useQueryClient();
  const [condition, setCondition] = useState<'GOOD' | 'DAMAGED' | 'LOST'>('GOOD');
  const [note, setNote] = useState('');
  const [fee, setFee] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const feePaise = fee ? Math.round(Number(fee) * 100) : undefined;
      const r = await api.returnLoan(loan!.id, { condition, note: note || undefined,
        damageFeePaise: condition === 'DAMAGED' ? feePaise : undefined, lostFeePaise: condition === 'LOST' ? feePaise : undefined });
      toast.success(r.finesTotalPaise > 0 ? `Return recorded. Fine created: ${money(r.finesTotalPaise)}` : 'Return recorded. No fine due.');
      qc.invalidateQueries({ queryKey: ['manage', libraryId] });
      setCondition('GOOD'); setNote(''); setFee('');
      onClose();
    } catch (e) {
      setError(isApiError(e) ? e.message : 'Could not record the return.');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal open={!!loan} onOpenChange={(o) => !o && !busy && onClose()} title="Process return" description={loan ? `“${loan.book.title}” (${loan.accessionNo}) from ${loan.member?.fullName}` : undefined}
      footer={<><Button variant="ghost" onClick={onClose} disabled={busy}>Cancel</Button><Button loading={busy} onClick={submit}>Record return</Button></>}>
      {loan && (
        <div className="space-y-4">
          {loan.overdue
            ? <Alert tone="warning" title={`${loan.overdueDays} days overdue`}>An overdue fine of about {money(loan.estimatedFinePaise)} will be created automatically per your library policy.</Alert>
            : <Alert tone="success">Returned on time — no overdue fine.</Alert>}
          <fieldset>
            <legend className="mb-2 text-sm font-medium">Condition</legend>
            <Segmented label="Condition" value={condition} onChange={setCondition} options={[{ value: 'GOOD', label: 'Good' }, { value: 'DAMAGED', label: 'Damaged' }, { value: 'LOST', label: 'Lost' }]} />
          </fieldset>
          {condition !== 'GOOD' && (
            <Field label={condition === 'LOST' ? 'Replacement charge (₹)' : 'Damage charge (₹)'} htmlFor="fee" hint="Optional — creates a separate fine">
              <Input id="fee" type="number" min={0} step="1" value={fee} onChange={(e) => setFee(e.target.value)} />
            </Field>
          )}
          <Field label="Note" htmlFor="ret-note" hint="Optional"><Textarea id="ret-note" rows={2} value={note} onChange={(e) => setNote(e.target.value)} /></Field>
          {error && <Alert tone="error">{error}</Alert>}
        </div>
      )}
    </Modal>
  );
}
