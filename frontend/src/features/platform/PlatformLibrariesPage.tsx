import { useState } from 'react';
import { Link } from 'react-router';
import { useForm } from 'react-hook-form';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Building2, Plus } from 'lucide-react';
import { platformApi } from '@/api/endpoints';
import { isApiError } from '@/api/http';
import { Modal } from '@/components/modal';
import { EmptyState, QueryState } from '@/components/states';
import { DataTable } from '@/components/table';
import { Alert, Button, PageHeader } from '@/components/ui';
import { Field, Input } from '@/components/form';
import { StatusBadge } from '@/components/status';
import { money, num } from '@/lib/format';

export default function PlatformLibrariesPage() {
  const libraries = useQuery({ queryKey: ['platform', 'libraries'], queryFn: platformApi.libraries });
  const [creating, setCreating] = useState(false);
  return (
    <>
      <PageHeader title="Libraries" subtitle="Every library on the platform, with live operational totals." icon={<Building2 className="size-5" />}
        actions={<Button icon={<Plus className="size-4" />} onClick={() => setCreating(true)}>New library</Button>} />
      <div className="glass p-4">
        <QueryState query={libraries} isEmpty={(d) => d.length === 0} empty={<EmptyState title="No libraries yet" action={<Button onClick={() => setCreating(true)}>Create the first library</Button>} />}>
          {(d) => (
            <DataTable caption="Libraries" rows={d} rowKey={(l) => l.id} columns={[
              { key: 'name', header: 'Library', primary: true, sortValue: (l) => l.name, cell: (l) => <Link to={`/app/super-admin/libraries/${l.id}`} className="font-medium text-fg hover:text-accent-2">{l.name}<span className="block text-xs text-faint">{l.code}{l.city ? ` · ${l.city}` : ''}</span></Link> },
              { key: 'titles', header: 'Titles', sortValue: (l) => l.titles, cell: (l) => num(l.titles) },
              { key: 'copies', header: 'Copies', sortValue: (l) => l.copies, cell: (l) => num(l.copies) },
              { key: 'loans', header: 'Active loans', sortValue: (l) => l.activeLoans, cell: (l) => <span>{num(l.activeLoans)}{l.overdueLoans ? <span className="text-danger"> · {l.overdueLoans} overdue</span> : ''}</span> },
              { key: 'members', header: 'Members', sortValue: (l) => l.members, cell: (l) => num(l.members) },
              { key: 'pending', header: 'Pending', sortValue: (l) => l.pendingRequests, cell: (l) => num(l.pendingRequests) },
              { key: 'fines', header: 'Collected', sortValue: (l) => l.finesCollectedPaise, cell: (l) => money(l.finesCollectedPaise) },
              { key: 'status', header: 'Status', cell: (l) => <StatusBadge status={l.status} context="account" /> },
            ]} />
          )}
        </QueryState>
      </div>
      <CreateLibraryDialog open={creating} onClose={() => setCreating(false)} />
    </>
  );
}

function CreateLibraryDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const { register, handleSubmit, reset, formState: { isSubmitting } } = useForm<{ code: string; name: string; city: string; tagline: string; openingHours: string; publicEmail: string }>();
  const submit = handleSubmit(async (v) => {
    setError(null);
    try {
      const l = await platformApi.createLibrary({ code: v.code, profile: { name: v.name, city: v.city || null, tagline: v.tagline || null, openingHours: v.openingHours || null, publicEmail: v.publicEmail || null } });
      toast.success(`${l.name} created`);
      qc.invalidateQueries({ queryKey: ['platform'] });
      qc.invalidateQueries({ queryKey: ['public'] });
      reset();
      onClose();
    } catch (e) {
      setError(isApiError(e) ? (e.fieldErrors[0] ? `${e.fieldErrors[0].field}: ${e.fieldErrors[0].message}` : e.message) : 'Could not create the library');
    }
  });
  return (
    <Modal open={open} onOpenChange={(o) => !o && onClose()} title="Create a library" description="Default borrowing policy is applied; the library's admin can change it."
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button type="submit" form="new-library" loading={isSubmitting}>Create library</Button></>}>
      <form id="new-library" onSubmit={submit} noValidate className="grid gap-4 sm:grid-cols-2">
        <Field label="Code" htmlFor="nl-code" hint="2–12 letters/digits, e.g. NTH" required><Input id="nl-code" {...register('code', { required: true })} /></Field>
        <Field label="City" htmlFor="nl-city"><Input id="nl-city" {...register('city')} /></Field>
        <Field label="Library name" htmlFor="nl-name" required className="sm:col-span-2"><Input id="nl-name" {...register('name', { required: true })} /></Field>
        <Field label="Tagline" htmlFor="nl-tagline" className="sm:col-span-2"><Input id="nl-tagline" {...register('tagline')} /></Field>
        <Field label="Opening hours" htmlFor="nl-hours"><Input id="nl-hours" {...register('openingHours')} /></Field>
        <Field label="Public email" htmlFor="nl-email"><Input id="nl-email" type="email" {...register('publicEmail')} /></Field>
        {error && <div className="sm:col-span-2"><Alert tone="error">{error}</Alert></div>}
      </form>
    </Modal>
  );
}
