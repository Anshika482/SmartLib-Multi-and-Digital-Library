import { useState } from 'react';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Check, Crown, MailWarning, Search, ShieldCheck, UserCheck, X } from 'lucide-react';
import { platformApi } from '@/api/endpoints';
import type { AdminApplication } from '@/api/types';
import { Input } from '@/components/form';
import { ConfirmDialog } from '@/components/modal';
import { EmptyState, QueryState } from '@/components/states';
import { StatusBadge } from '@/components/status';
import { DataTable, Pagination } from '@/components/table';
import { Badge, Button, PageHeader, Panel, Segmented } from '@/components/ui';
import { useDebounced } from '@/features/staff/useDebounced';
import { date } from '@/lib/format';

type RoleFilter = 'ALL' | 'ADMIN' | 'SUPER_ADMIN';
type StatusFilter = 'PENDING' | 'UNVERIFIED' | 'APPROVED' | 'REJECTED' | 'ALL';

/** SUPER_ADMIN review of self-registered admin and Super Admin applications (librarians are approved by their library's admin). */
export default function PlatformApplicationsPage() {
  const qc = useQueryClient();
  const [role, setRole] = useState<RoleFilter>('ALL');
  const [status, setStatus] = useState<StatusFilter>('PENDING');
  const [page, setPage] = useState(0);
  const [q, setQ, debounced] = useDebounced('');
  const apps = useQuery({
    queryKey: ['platform', 'applications', role, status, debounced, page],
    queryFn: () => platformApi.applications({ role, status, q: debounced || undefined, page }),
    placeholderData: keepPreviousData,
  });
  const [action, setAction] = useState<{ kind: 'approve' | 'reject'; app: AdminApplication } | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: ['platform'] });
  const superAdmin = action?.app.role === 'SUPER_ADMIN';

  return (
    <>
      <PageHeader title="Admin applications" icon={<UserCheck className="size-5" />}
        subtitle="People who registered as Admin or Super Admin. Nobody gets access until they verify their email and you approve them." />
      <Panel title="Applications" actions={
        <Input aria-label="Search applications" leading={<Search className="size-4" />} placeholder="Name or email…" className="w-56"
          value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />}>
        <div className="mb-4 flex flex-wrap gap-3">
          <Segmented label="Role" value={role} onChange={(v) => { setRole(v); setPage(0); }}
            options={[{ value: 'ALL', label: 'All roles' }, { value: 'ADMIN', label: 'Admin' }, { value: 'SUPER_ADMIN', label: 'Super Admin' }]} />
          <Segmented label="Status" value={status} onChange={(v) => { setStatus(v); setPage(0); }}
            options={[{ value: 'PENDING', label: 'Pending' }, { value: 'UNVERIFIED', label: 'Email unverified' }, { value: 'APPROVED', label: 'Approved' },
              { value: 'REJECTED', label: 'Rejected' }, { value: 'ALL', label: 'All' }]} />
        </div>
        <QueryState query={apps} isEmpty={(d) => d.content.length === 0}
          empty={<EmptyState title="No applications here" description="New admin and Super Admin applications appear here, and you're notified by email." />}>
          {(d) => (
            <>
              <DataTable caption="Admin and Super Admin applications" rows={d.content} rowKey={(a) => a.id} columns={[
                { key: 'name', header: 'Applicant', primary: true, cell: (a) => (
                  <div><p className="font-medium text-fg">{a.fullName}</p><p className="text-xs text-faint">{a.email}{a.phone ? ` · ${a.phone}` : ''}</p></div>) },
                { key: 'role', header: 'Requested access', cell: (a) => (
                  <div>
                    {a.role === 'SUPER_ADMIN'
                      ? <Badge tone="red" icon={<Crown className="size-3" />}>Super Admin</Badge>
                      : <Badge tone="violet" icon={<ShieldCheck className="size-3" />}>Admin</Badge>}
                    <p className="mt-1 text-xs text-muted">{a.role === 'SUPER_ADMIN' ? 'Whole platform' : a.libraryName}</p>
                  </div>) },
                { key: 'who', header: 'Designation', cell: (a) => (
                  <div className="text-xs"><p className="text-fg">{a.designation ?? '—'}</p>{a.employeeRef && <p className="text-faint">ID {a.employeeRef}</p>}</div>) },
                { key: 'reason', header: 'Reason', cell: (a) => <p className="max-w-xs text-xs text-muted" title={a.applicationNote ?? undefined}>{a.applicationNote ?? '—'}</p> },
                { key: 'email', header: 'Email', cell: (a) => a.emailVerified ? <Badge tone="green">Verified</Badge>
                  : <Badge tone="amber" icon={<MailWarning className="size-3" />}>Unverified</Badge> },
                { key: 'applied', header: 'Applied', sortValue: (a) => a.appliedAt, cell: (a) => date(a.appliedAt) },
                { key: 'status', header: 'Status', cell: (a) => (
                  <div>
                    <StatusBadge status={a.status} context="approval" />
                    {a.reviewedByName && a.status !== 'PENDING' && <p className="mt-1 text-[11px] text-faint">by {a.reviewedByName}</p>}
                    {a.rejectionReason && <p className="mt-1 text-[11px] text-faint">{a.rejectionReason}</p>}
                  </div>) },
                { key: 'actions', header: '', align: 'right', cell: (a) => a.status === 'PENDING' ? (
                  <div className="flex justify-end gap-1.5">
                    <Button size="sm" variant="success" icon={<Check className="size-3.5" />} disabled={!a.emailVerified}
                      title={a.emailVerified ? undefined : 'The applicant must verify their email first'} onClick={() => setAction({ kind: 'approve', app: a })}>Approve</Button>
                    <Button size="sm" variant="soft-danger" icon={<X className="size-3.5" />} onClick={() => setAction({ kind: 'reject', app: a })}>Reject</Button>
                  </div>) : null },
              ]} />
              <Pagination page={d.page} totalPages={d.totalPages} size={d.size} totalElements={d.totalElements} onChange={setPage} label="applications" />
            </>
          )}
        </QueryState>
      </Panel>
      <ConfirmDialog open={action?.kind === 'approve'} onOpenChange={(o) => !o && setAction(null)} tone="success"
        confirmLabel={superAdmin ? 'Approve Super Admin' : 'Approve admin'} title={`Approve ${action?.app.fullName}?`}
        description={superAdmin
          ? 'They will have full control of every library, user, setting and audit log on SmartLib. Only approve someone you can identify.'
          : `They will manage ${action?.app.libraryName ?? 'this library'}: its librarians, members, policies, fines, reports and settings.`}
        onConfirm={async () => { await platformApi.approveApplication(action!.app.id); toast.success(superAdmin ? 'Super Admin approved' : 'Admin approved'); refresh(); }} />
      <ConfirmDialog open={action?.kind === 'reject'} onOpenChange={(o) => !o && setAction(null)} tone="danger" confirmLabel="Reject application"
        title={`Reject ${action?.app.fullName}?`} reason={{ label: 'Reason (sent to the applicant)' }}
        onConfirm={async (r) => { await platformApi.rejectApplication(action!.app.id, r); toast.success('Application rejected'); refresh(); }} />
    </>
  );
}
