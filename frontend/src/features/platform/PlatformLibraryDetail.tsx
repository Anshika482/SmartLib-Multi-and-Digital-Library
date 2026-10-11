import { useState } from 'react';
import { useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Building2, MailPlus, Power } from 'lucide-react';
import { platformApi } from '@/api/endpoints';
import { isApiError } from '@/api/http';
import { LibraryArt } from '@/components/book';
import { ConfirmDialog, Modal } from '@/components/modal';
import { QueryState } from '@/components/states';
import { DataTable } from '@/components/table';
import { Alert, Badge, Button, KeyValue, PageHeader, Panel, StatCard } from '@/components/ui';
import { Field, Input } from '@/components/form';
import { StatusBadge } from '@/components/status';
import { date, money, num, titleCase } from '@/lib/format';
import { BookCopy, ClipboardList, Repeat2, Users } from 'lucide-react';

export default function PlatformLibraryDetail() {
  const { id } = useParams();
  const libraryId = Number(id);
  const qc = useQueryClient();
  const data = useQuery({ queryKey: ['platform', 'library', libraryId], queryFn: () => platformApi.library(libraryId) });
  const [statusOpen, setStatusOpen] = useState(false);
  const [invite, setInvite] = useState(false);
  const [email, setEmail] = useState('');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: ['platform'] });
  return (
    <QueryState query={data}>
      {({ library: l, summary: s, staff }) => (
        <>
          <PageHeader title={l.name} subtitle={`${l.code} · ${l.city ?? ''}`} icon={<Building2 className="size-5" />}
            crumbs={[{ label: 'Libraries', to: '/app/super-admin/libraries' }, { label: l.name }]}
            actions={<>
              <Button variant="outline" icon={<MailPlus className="size-4" />} onClick={() => setInvite(true)}>Invite admin</Button>
              <Button variant={l.status === 'ACTIVE' ? 'soft-danger' : 'success'} icon={<Power className="size-4" />} onClick={() => setStatusOpen(true)}>{l.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}</Button>
            </>} />
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <StatCard label="Titles" value={num(s.titles)} hint={`${num(s.copies)} copies`} icon={<BookCopy className="size-5" />} />
            <StatCard label="Active loans" value={num(s.activeLoans)} hint={`${num(s.overdueLoans)} overdue`} icon={<Repeat2 className="size-5" />} tone="cyan" />
            <StatCard label="Pending requests" value={num(s.pendingRequests)} icon={<ClipboardList className="size-5" />} tone="amber" />
            <StatCard label="Members" value={num(s.totalMembers)} hint={`Collected ${money(s.finesCollectedTotalPaise)}`} icon={<Users className="size-5" />} tone="violet" />
          </div>
          <div className="mt-6 grid gap-6 xl:grid-cols-[1fr_1.4fr]">
            <Panel title="Profile">
              <div className="mb-4 h-32 overflow-hidden rounded-xl"><LibraryArt library={l} /></div>
              <KeyValue items={[
                { label: 'Status', value: <StatusBadge status={l.status} context="account" /> }, { label: 'Slug', value: l.slug },
                { label: 'Address', value: [l.addressLine, l.city, l.state].filter(Boolean).join(', ') || '—' }, { label: 'Hours', value: l.openingHours },
                { label: 'Email', value: l.publicEmail }, { label: 'Phone', value: l.publicPhone },
                { label: 'Loan period', value: `${l.policy.loanPeriodDays} days` }, { label: 'Fine per day', value: money(l.policy.finePerDayPaise) },
              ]} />
            </Panel>
            <Panel title="Staff">
              <DataTable caption="Staff" rows={staff} rowKey={(u) => u.id} columns={[
                { key: 'name', header: 'Name', primary: true, cell: (u) => <div><p className="font-medium text-fg">{u.fullName}</p><p className="text-xs text-faint">{u.email}</p></div> },
                { key: 'role', header: 'Role', cell: (u) => <Badge tone={u.role === 'ADMIN' ? 'violet' : 'blue'}>{titleCase(u.role)}</Badge> },
                { key: 'approval', header: 'Approval', cell: (u) => u.approvalStatus ? <StatusBadge status={u.approvalStatus} context="approval" /> : '—' },
                { key: 'account', header: 'Account', cell: (u) => <StatusBadge status={u.status} context="account" /> },
                { key: 'since', header: 'Since', cell: (u) => date(u.createdAt) },
              ]} />
              <p className="mt-3 text-xs text-faint">To review librarian applications for this library, use its admin workspace or ask its administrator. Super admins can also call the library-scoped APIs directly.</p>
            </Panel>
          </div>
          <ConfirmDialog open={statusOpen} onOpenChange={setStatusOpen} tone={l.status === 'ACTIVE' ? 'danger' : 'success'} confirmLabel={l.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
            title={`${l.status === 'ACTIVE' ? 'Deactivate' : 'Activate'} ${l.name}?`}
            description={l.status === 'ACTIVE' ? 'It disappears from the public catalogue and directory; existing records are kept.' : 'It becomes visible in the public catalogue again.'}
            onConfirm={async () => { await platformApi.setLibraryStatus(libraryId, l.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'); toast.success('Library status updated'); refresh(); qc.invalidateQueries({ queryKey: ['public'] }); }} />
          <Modal open={invite} onOpenChange={setInvite} title="Invite a library administrator" description="They receive a single-use link (72 hours) to set their own password. No password is sent by email."
            footer={<><Button variant="ghost" onClick={() => setInvite(false)}>Cancel</Button><Button loading={busy} disabled={!email || name.trim().length < 2} onClick={async () => {
              setBusy(true); setError(null);
              try {
                await platformApi.inviteAdmin(libraryId, { email, fullName: name });
                toast.success('Invitation sent');
                setInvite(false); setEmail(''); setName(''); refresh();
              } catch (e) { setError(isApiError(e) ? (e.fieldErrors[0]?.message ?? e.message) : 'Could not send invitation'); } finally { setBusy(false); }
            }}>Send invitation</Button></>}>
            <div className="space-y-4">
              <Field label="Full name" htmlFor="inv-name" required><Input id="inv-name" value={name} onChange={(e) => setName(e.target.value)} /></Field>
              <Field label="Email" htmlFor="inv-email" required><Input id="inv-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
              {error && <Alert tone="error">{error}</Alert>}
            </div>
          </Modal>
        </>
      )}
    </QueryState>
  );
}
