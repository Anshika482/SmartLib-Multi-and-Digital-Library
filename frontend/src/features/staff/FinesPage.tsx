import { useState } from 'react';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { HandCoins, Plus, Search } from 'lucide-react';
import { isApiError } from '@/api/http';
import type { FineType, FineView } from '@/api/types';
import { ConfirmDialog, Modal } from '@/components/modal';
import { EmptyState, QueryState } from '@/components/states';
import { DataTable, Pagination } from '@/components/table';
import { Alert, Badge, Button, PageHeader, Segmented } from '@/components/ui';
import { Field, Input, Select, Textarea } from '@/components/form';
import { StatusBadge } from '@/components/status';
import { date, dateTime, money, titleCase } from '@/lib/format';
import { useStaff } from './useStaff';
import { useDebounced } from './useDebounced';

export default function FinesPage() {
  const { api, libraryId, isAdmin } = useStaff();
  const qc = useQueryClient();
  const [view, setView] = useState<'fines' | 'payments'>('fines');
  const [status, setStatus] = useState<'UNPAID' | 'PAID' | 'WAIVED' | 'ALL'>('UNPAID');
  const [page, setPage] = useState(0);
  const [q, setQ, debounced] = useDebounced('');
  const fines = useQuery({ queryKey: ['manage', libraryId, 'fines', status, debounced, page], queryFn: () => api.fines({ status, q: debounced || undefined, page, size: 15 }),
    placeholderData: keepPreviousData, enabled: view === 'fines' });
  const payments = useQuery({ queryKey: ['manage', libraryId, 'payments', page], queryFn: () => api.payments({ page }), placeholderData: keepPreviousData, enabled: view === 'payments' });
  const [waiving, setWaiving] = useState<FineView | null>(null);
  const [creating, setCreating] = useState(false);
  return (
    <>
      <PageHeader title={isAdmin ? 'Fines & payments' : 'Fines'} subtitle="Overdue fines are created automatically on return. Payments are verified server-side with Razorpay." icon={<HandCoins className="size-5" />}
        actions={<Button icon={<Plus className="size-4" />} onClick={() => setCreating(true)}>Add fine</Button>} />
      <div className="mb-5 flex flex-wrap items-center gap-3">
        <Segmented label="View" value={view} onChange={(v) => { setView(v); setPage(0); }} options={[{ value: 'fines', label: 'Fines' }, { value: 'payments', label: 'Payments' }]} />
        {view === 'fines' && <>
          <Segmented label="Fine status" value={status} onChange={(v) => { setStatus(v); setPage(0); }} options={[{ value: 'UNPAID', label: 'Unpaid' }, { value: 'PAID', label: 'Paid' }, { value: 'WAIVED', label: 'Waived' }, { value: 'ALL', label: 'All' }]} />
          <div className="w-full max-w-xs"><label htmlFor="fn-search" className="sr-only">Search fines</label>
            <Input id="fn-search" leading={<Search className="size-4" />} placeholder="Member name or email…" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} /></div>
        </>}
      </div>
      <div className="glass p-4">
        {view === 'fines' ? (
          <QueryState query={fines} isEmpty={(d) => d.content.length === 0} empty={<EmptyState title="No fines here" />}>
            {(d) => (
              <>
                <DataTable caption="Fines" rows={d.content} rowKey={(f) => f.id} columns={[
                  { key: 'member', header: 'Member', primary: true, cell: (f) => <div><p className="font-medium text-fg">{f.member?.fullName}</p><p className="text-xs text-faint">{f.member?.email}</p></div> },
                  { key: 'type', header: 'Type', cell: (f) => titleCase(f.type) },
                  { key: 'book', header: 'Details', cell: (f) => <span className="text-xs">{f.book?.title ? `${f.book.title} · ` : ''}{f.reason}</span> },
                  { key: 'date', header: 'Created', sortValue: (f) => f.createdAt, cell: (f) => date(f.createdAt) },
                  { key: 'amount', header: 'Amount', align: 'right', sortValue: (f) => f.amountPaise, cell: (f) => <b className="text-fg">{money(f.amountPaise)}</b> },
                  { key: 'status', header: 'Status', cell: (f) => <div><StatusBadge status={f.status} />{f.waivedReason && <p className="mt-1 text-[11px] text-faint">{f.waivedReason}</p>}</div> },
                  { key: 'actions', header: '', align: 'right', cell: (f) => f.status === 'UNPAID' && isAdmin ? <Button size="sm" variant="outline" onClick={() => setWaiving(f)}>Waive</Button> : null },
                ]} />
                <Pagination page={d.page} totalPages={d.totalPages} size={d.size} totalElements={d.totalElements} onChange={setPage} label="fines" />
              </>
            )}
          </QueryState>
        ) : (
          <QueryState query={payments} isEmpty={(d) => d.content.length === 0} empty={<EmptyState title="No payments yet" />}>
            {(d) => (
              <>
                <DataTable caption="Payments" rows={d.content} rowKey={(p) => p.id} columns={[
                  { key: 'date', header: 'Date', primary: true, cell: (p) => dateTime(p.verifiedAt ?? p.createdAt) },
                  { key: 'member', header: 'Member', cell: (p) => p.member?.fullName },
                  { key: 'for', header: 'For', cell: (p) => `${titleCase(p.fineType)}${p.book ? ` · ${p.book.title}` : ''}` },
                  { key: 'amount', header: 'Amount', align: 'right', cell: (p) => <b className="text-fg">{money(p.amountPaise)}</b> },
                  { key: 'ref', header: 'Payment ID', cell: (p) => <span className="inline-flex items-center gap-1.5"><span className="font-mono text-xs">{p.providerPaymentId ?? p.providerOrderId}</span>{p.provider === 'DEMO' && <Badge tone="amber">Demo</Badge>}</span> },
                  { key: 'status', header: 'Status', cell: (p) => <StatusBadge status={p.status} /> },
                ]} />
                <Pagination page={d.page} totalPages={d.totalPages} size={d.size} totalElements={d.totalElements} onChange={setPage} label="payments" />
              </>
            )}
          </QueryState>
        )}
      </div>
      <ConfirmDialog open={!!waiving} onOpenChange={(o) => !o && setWaiving(null)} title="Waive this fine?" tone="primary" confirmLabel="Waive fine"
        description={waiving ? `${money(waiving.amountPaise)} for ${waiving.member?.fullName}. The member will be notified.` : undefined}
        reason={{ label: 'Reason', placeholder: 'e.g. Library closure during the due week' }}
        onConfirm={async (r) => { await api.waiveFine(waiving!.id, r); toast.success('Fine waived'); qc.invalidateQueries({ queryKey: ['manage', libraryId] }); }} />
      <CreateFineDialog open={creating} onClose={() => setCreating(false)} />
    </>
  );
}

function CreateFineDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { api, libraryId } = useStaff();
  const qc = useQueryClient();
  const [q, setQ, debounced] = useDebounced('');
  const members = useQuery({ queryKey: ['manage', libraryId, 'members', 'pick', debounced], queryFn: () => api.members({ q: debounced || undefined }), enabled: open });
  const [memberId, setMemberId] = useState('');
  const [type, setType] = useState<FineType>('DAMAGED');
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submit = async () => {
    setError(null);
    setBusy(true);
    try {
      await api.createFine({ memberId: Number(memberId), type, amountPaise: Math.round(Number(amount) * 100), reason });
      toast.success('Fine created and member notified');
      qc.invalidateQueries({ queryKey: ['manage', libraryId] });
      setMemberId(''); setAmount(''); setReason('');
      onClose();
    } catch (e) {
      setError(isApiError(e) ? (e.fieldErrors[0]?.message ?? e.message) : 'Could not create the fine.');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal open={open} onOpenChange={(o) => !o && !busy && onClose()} title="Add a fine" description="For damaged or lost books, or other library charges. Overdue fines are automatic."
      footer={<><Button variant="ghost" onClick={onClose} disabled={busy}>Cancel</Button>
        <Button loading={busy} disabled={!memberId || !(Number(amount) >= 1) || reason.trim().length < 3} onClick={submit}>Create fine</Button></>}>
      <div className="space-y-4">
        <Field label="Find member" htmlFor="fm-search"><Input id="fm-search" placeholder="Search members of this library…" value={q} onChange={(e) => setQ(e.target.value)} /></Field>
        <Field label="Member" htmlFor="fm-member" required>
          <Select id="fm-member" value={memberId} onChange={(e) => setMemberId(e.target.value)}>
            <option value="">Choose a member…</option>
            {members.data?.content.map((m) => <option key={m.userId} value={m.userId}>{m.fullName} — {m.email}</option>)}
          </Select>
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Type" htmlFor="fm-type"><Select id="fm-type" value={type} onChange={(e) => setType(e.target.value as FineType)}>
            <option value="DAMAGED">Damaged book</option><option value="LOST">Lost book</option><option value="MANUAL">Other charge</option></Select></Field>
          <Field label="Amount (₹)" htmlFor="fm-amount" required><Input id="fm-amount" type="number" min={1} value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
        </div>
        <Field label="Reason" htmlFor="fm-reason" required><Textarea id="fm-reason" rows={2} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} /></Field>
        {error && <Alert tone="error">{error}</Alert>}
      </div>
    </Modal>
  );
}
