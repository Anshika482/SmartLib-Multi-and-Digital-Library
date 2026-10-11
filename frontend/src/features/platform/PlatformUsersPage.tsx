import { useState } from 'react';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Search, Users } from 'lucide-react';
import { platformApi } from '@/api/endpoints';
import type { UserRow } from '@/api/types';
import { ConfirmDialog } from '@/components/modal';
import { EmptyState, QueryState } from '@/components/states';
import { DataTable, Pagination } from '@/components/table';
import { Badge, Button, PageHeader } from '@/components/ui';
import { Input, Select } from '@/components/form';
import { StatusBadge } from '@/components/status';
import { date, relative, titleCase } from '@/lib/format';
import { useSession } from '@/stores/session';
import { useDebounced } from '@/features/staff/useDebounced';

export default function PlatformUsersPage() {
  const me = useSession((s) => s.user);
  const qc = useQueryClient();
  const [role, setRole] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [q, setQ, debounced] = useDebounced('');
  const users = useQuery({ queryKey: ['platform', 'users', role, status, debounced, page], queryFn: () => platformApi.users({ role: role || undefined, status: status || undefined, q: debounced || undefined, page }), placeholderData: keepPreviousData });
  const [target, setTarget] = useState<{ user: UserRow; to: string } | null>(null);
  return (
    <>
      <PageHeader title="Users" subtitle="All platform accounts. Suspending a user signs them out everywhere immediately." icon={<Users className="size-5" />} />
      <div className="mb-5 grid gap-3 sm:grid-cols-3">
        <Input aria-label="Search users" leading={<Search className="size-4" />} placeholder="Name or email…" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />
        <Select aria-label="Role" value={role} onChange={(e) => { setRole(e.target.value); setPage(0); }}>
          <option value="">All roles</option>{['MEMBER', 'LIBRARIAN', 'ADMIN', 'SUPER_ADMIN'].map((r) => <option key={r} value={r}>{titleCase(r)}</option>)}
        </Select>
        <Select aria-label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
          <option value="">All statuses</option>{['ACTIVE', 'SUSPENDED', 'DISABLED', 'INVITED'].map((s) => <option key={s} value={s}>{titleCase(s)}</option>)}
        </Select>
      </div>
      <div className="glass p-4">
        <QueryState query={users} isEmpty={(d) => d.content.length === 0} empty={<EmptyState title="No users match" />}>
          {(d) => (
            <>
              <DataTable caption="Users" rows={d.content} rowKey={(u) => u.id} columns={[
                { key: 'name', header: 'User', primary: true, cell: (u) => <div><p className="font-medium text-fg">{u.fullName}</p><p className="text-xs text-faint">{u.email}</p></div> },
                { key: 'role', header: 'Role', cell: (u) => <div><Badge tone={u.role === 'MEMBER' ? 'gray' : u.role === 'SUPER_ADMIN' ? 'red' : 'violet'}>{titleCase(u.role)}</Badge>{u.libraryName && <p className="mt-1 text-[11px] text-faint">{u.libraryName}{u.approvalStatus && u.approvalStatus !== 'APPROVED' ? ` · ${titleCase(u.approvalStatus)}` : ''}</p>}</div> },
                { key: 'verified', header: 'Email', cell: (u) => u.emailVerified ? <Badge tone="green">Verified</Badge> : <Badge tone="amber">Unverified</Badge> },
                { key: 'joined', header: 'Joined', cell: (u) => date(u.createdAt) },
                { key: 'last', header: 'Last login', cell: (u) => u.lastLoginAt ? relative(u.lastLoginAt) : 'Never' },
                { key: 'status', header: 'Status', cell: (u) => <StatusBadge status={u.status} context="account" /> },
                { key: 'actions', header: '', align: 'right', cell: (u) => u.id === me?.id || u.status === 'INVITED' ? null : u.status === 'ACTIVE'
                  ? <Button size="sm" variant="soft-danger" onClick={() => setTarget({ user: u, to: 'SUSPENDED' })}>Suspend</Button>
                  : <Button size="sm" variant="outline" onClick={() => setTarget({ user: u, to: 'ACTIVE' })}>Reactivate</Button> },
              ]} />
              <Pagination page={d.page} totalPages={d.totalPages} size={d.size} totalElements={d.totalElements} onChange={setPage} label="users" />
            </>
          )}
        </QueryState>
      </div>
      <ConfirmDialog open={!!target} onOpenChange={(o) => !o && setTarget(null)} tone={target?.to === 'ACTIVE' ? 'success' : 'danger'}
        confirmLabel={target?.to === 'ACTIVE' ? 'Reactivate' : 'Suspend'} title={`${target?.to === 'ACTIVE' ? 'Reactivate' : 'Suspend'} ${target?.user.fullName}?`}
        description={target?.to === 'ACTIVE' ? 'They can log in again.' : 'They are signed out immediately and cannot log in until reactivated.'}
        onConfirm={async () => { await platformApi.setUserStatus(target!.user.id, target!.to); toast.success('User updated'); qc.invalidateQueries({ queryKey: ['platform', 'users'] }); }} />
    </>
  );
}
