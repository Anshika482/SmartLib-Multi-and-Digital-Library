import { useState } from 'react';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Check, MailWarning, UserCog, X } from 'lucide-react';
import type { Application } from '@/api/types';
import { ConfirmDialog } from '@/components/modal';
import { EmptyState, QueryState } from '@/components/states';
import { DataTable, Pagination } from '@/components/table';
import { Badge, Button, PageHeader, Panel, Segmented } from '@/components/ui';
import { StatusBadge } from '@/components/status';
import { date, titleCase } from '@/lib/format';
import { useStaff } from './useStaff';

type Filter = 'PENDING' | 'UNVERIFIED' | 'REJECTED' | 'ALL';

export default function LibrariansPage() {
  const { api, libraryId, user } = useStaff();
  const qc = useQueryClient();
  const [filter, setFilter] = useState<Filter>('PENDING');
  const [page, setPage] = useState(0);
  const apps = useQuery({ queryKey: ['manage', libraryId, 'applications', filter, page], queryFn: () => api.applications({ status: filter, page }), placeholderData: keepPreviousData });
  const staff = useQuery({ queryKey: ['manage', libraryId, 'staff'], queryFn: api.staff });
  const [action, setAction] = useState<{ kind: 'approve' | 'reject' | 'revoke'; app: Application } | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: ['manage', libraryId] });
  return (
    <>
      <PageHeader title="Librarians" subtitle="Email verification and administrator approval are separate: applicants must verify their email before they can be approved." icon={<UserCog className="size-5" />} />
      <Panel title="Applications" className="mb-6" actions={<Segmented label="Applications" value={filter} onChange={(v) => { setFilter(v); setPage(0); }}
        options={[{ value: 'PENDING', label: 'Pending approval' }, { value: 'UNVERIFIED', label: 'Email unverified' }, { value: 'REJECTED', label: 'Rejected' }, { value: 'ALL', label: 'All' }]} />}>
        <QueryState query={apps} isEmpty={(d) => d.content.length === 0} empty={<EmptyState title="No applications here" />}>
          {(d) => (
            <>
              <DataTable caption="Librarian applications" rows={d.content} rowKey={(a) => a.id} columns={[
                { key: 'name', header: 'Applicant', primary: true, cell: (a) => <div><p className="font-medium text-fg">{a.fullName}</p><p className="text-xs text-faint">{a.email}{a.phone ? ` · ${a.phone}` : ''}</p></div> },
                { key: 'email', header: 'Email', cell: (a) => a.emailVerified ? <Badge tone="green">Verified</Badge> : <Badge tone="amber" icon={<MailWarning className="size-3" />}>Unverified</Badge> },
                { key: 'details', header: 'Details', cell: (a) => <span className="text-xs">{a.employeeRef ? `ID ${a.employeeRef}` : ''}{a.applicationNote ? ` “${a.applicationNote}”` : a.employeeRef ? '' : '—'}</span> },
                { key: 'applied', header: 'Applied', sortValue: (a) => a.appliedAt, cell: (a) => date(a.appliedAt) },
                { key: 'status', header: 'Status', cell: (a) => <div><StatusBadge status={a.status} context="approval" />{a.rejectionReason && <p className="mt-1 text-[11px] text-faint">{a.rejectionReason}</p>}</div> },
                { key: 'actions', header: '', align: 'right', cell: (a) => a.status === 'PENDING' ? (
                  <div className="flex justify-end gap-1.5">
                    <Button size="sm" variant="success" icon={<Check className="size-3.5" />} disabled={!a.emailVerified} title={a.emailVerified ? undefined : 'The applicant must verify their email first'}
                      onClick={() => setAction({ kind: 'approve', app: a })}>Approve</Button>
                    <Button size="sm" variant="soft-danger" icon={<X className="size-3.5" />} onClick={() => setAction({ kind: 'reject', app: a })}>Reject</Button>
                  </div>) : null },
              ]} />
              <Pagination page={d.page} totalPages={d.totalPages} size={d.size} totalElements={d.totalElements} onChange={setPage} label="applications" />
            </>
          )}
        </QueryState>
      </Panel>
      <Panel title="Active staff">
        <QueryState query={staff} isEmpty={(d) => d.content.length === 0} empty={<EmptyState title="No staff yet" />}>
          {(d) => (
            <DataTable caption="Staff" rows={d.content} rowKey={(a) => a.id} columns={[
              { key: 'name', header: 'Name', primary: true, cell: (a) => <div><p className="font-medium text-fg">{a.fullName}</p><p className="text-xs text-faint">{a.email}</p></div> },
              { key: 'role', header: 'Role', cell: (a) => <Badge tone={a.role === 'ADMIN' ? 'violet' : 'blue'}>{titleCase(a.role)}</Badge> },
              { key: 'since', header: 'Approved', cell: (a) => date(a.reviewedAt ?? a.appliedAt) },
              { key: 'actions', header: '', align: 'right', cell: (a) => a.role === 'LIBRARIAN' && a.userId !== user.id ? <Button size="sm" variant="soft-danger" onClick={() => setAction({ kind: 'revoke', app: a })}>Revoke access</Button> : null },
            ]} />
          )}
        </QueryState>
      </Panel>
      <ConfirmDialog open={action?.kind === 'approve'} onOpenChange={(o) => !o && setAction(null)} tone="success" confirmLabel="Approve librarian"
        title={`Approve ${action?.app.fullName}?`} description="They'll be able to manage this library's books, requests, loans and fines."
        onConfirm={async () => { await api.approveApplication(action!.app.id); toast.success('Librarian approved'); refresh(); }} />
      <ConfirmDialog open={action?.kind === 'reject'} onOpenChange={(o) => !o && setAction(null)} tone="danger" confirmLabel="Reject application"
        title={`Reject ${action?.app.fullName}?`} reason={{ label: 'Reason (sent to the applicant)' }}
        onConfirm={async (r) => { await api.rejectApplication(action!.app.id, r); toast.success('Application rejected'); refresh(); }} />
      <ConfirmDialog open={action?.kind === 'revoke'} onOpenChange={(o) => !o && setAction(null)} tone="danger" confirmLabel="Revoke access"
        title={`Revoke ${action?.app.fullName}'s access?`} description="They are signed out immediately and can no longer access this library's workspace."
        onConfirm={async () => { await api.revokeStaff(action!.app.userId); toast.success('Access revoked'); refresh(); }} />
    </>
  );
}
