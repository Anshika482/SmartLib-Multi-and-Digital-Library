import { useState } from 'react';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Search, Users } from 'lucide-react';
import type { MemberRow } from '@/api/types';
import { ConfirmDialog } from '@/components/modal';
import { EmptyState, QueryState } from '@/components/states';
import { DataTable, Pagination } from '@/components/table';
import { Button, PageHeader, Segmented } from '@/components/ui';
import { Input } from '@/components/form';
import { StatusBadge } from '@/components/status';
import { date, money } from '@/lib/format';
import { useStaff } from './useStaff';
import { useDebounced } from './useDebounced';

export default function MembersPage() {
  const { api, libraryId, isAdmin, libraryName } = useStaff();
  const qc = useQueryClient();
  const [status, setStatus] = useState<'ALL' | 'ACTIVE' | 'SUSPENDED'>('ALL');
  const [page, setPage] = useState(0);
  const [q, setQ, debounced] = useDebounced('');
  const list = useQuery({ queryKey: ['manage', libraryId, 'members', status, debounced, page], queryFn: () => api.members({ status, q: debounced || undefined, page }), placeholderData: keepPreviousData });
  const [target, setTarget] = useState<MemberRow | null>(null);
  const suspend = target?.membershipStatus === 'ACTIVE';
  return (
    <>
      <PageHeader title="Members" subtitle={`People with a membership at ${libraryName}. ${isAdmin ? 'You can suspend or reactivate memberships.' : 'Read-only for librarians.'}`} icon={<Users className="size-5" />} />
      <div className="mb-5 flex flex-wrap items-center gap-3">
        <Segmented label="Membership status" value={status} onChange={(v) => { setStatus(v); setPage(0); }} options={[{ value: 'ALL', label: 'All' }, { value: 'ACTIVE', label: 'Active' }, { value: 'SUSPENDED', label: 'Suspended' }]} />
        <div className="w-full max-w-xs"><label htmlFor="mb-search" className="sr-only">Search members</label>
          <Input id="mb-search" leading={<Search className="size-4" />} placeholder="Name, email or phone…" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} /></div>
      </div>
      <div className="glass p-4">
        <QueryState query={list} isEmpty={(d) => d.content.length === 0} empty={<EmptyState icon={<Users className="size-6" />} title="No members found" />}>
          {(d) => (
            <>
              <DataTable caption="Members" rows={d.content} rowKey={(m) => m.userId} columns={[
                { key: 'name', header: 'Member', primary: true, sortValue: (m) => m.fullName, cell: (m) => <div><p className="font-medium text-fg">{m.fullName}</p><p className="text-xs text-faint">{m.email}{m.phone ? ` · ${m.phone}` : ''}</p></div> },
                { key: 'joined', header: 'Joined', sortValue: (m) => m.joinedAt, cell: (m) => date(m.joinedAt) },
                { key: 'loans', header: 'Loans', sortValue: (m) => m.activeLoans, cell: (m) => <span>{m.activeLoans} active{m.overdueLoans ? <span className="text-danger"> · {m.overdueLoans} overdue</span> : ''}</span> },
                { key: 'fines', header: 'Unpaid fines', sortValue: (m) => m.unpaidFinesPaise, cell: (m) => m.unpaidFinesPaise ? <span className="text-warning">{money(m.unpaidFinesPaise)}</span> : '—' },
                { key: 'status', header: 'Membership', cell: (m) => <StatusBadge status={m.membershipStatus} context="account" /> },
                ...(isAdmin ? [{ key: 'actions', header: '', align: 'right' as const, cell: (m: MemberRow) => (
                  <Button size="sm" variant={m.membershipStatus === 'ACTIVE' ? 'soft-danger' : 'outline'} onClick={() => setTarget(m)}>{m.membershipStatus === 'ACTIVE' ? 'Suspend' : 'Reactivate'}</Button>) }] : []),
              ]} />
              <Pagination page={d.page} totalPages={d.totalPages} size={d.size} totalElements={d.totalElements} onChange={setPage} label="members" />
            </>
          )}
        </QueryState>
      </div>
      <ConfirmDialog open={!!target} onOpenChange={(o) => !o && setTarget(null)} tone={suspend ? 'danger' : 'success'} confirmLabel={suspend ? 'Suspend membership' : 'Reactivate'}
        title={suspend ? `Suspend ${target?.fullName}?` : `Reactivate ${target?.fullName}?`}
        description={suspend ? 'They will not be able to request or borrow from this library until reactivated.' : 'They can request and borrow from this library again.'}
        onConfirm={async () => { await api.setMembership(target!.userId, suspend ? 'SUSPENDED' : 'ACTIVE'); toast.success('Membership updated'); qc.invalidateQueries({ queryKey: ['manage', libraryId, 'members'] }); }} />
    </>
  );
}
