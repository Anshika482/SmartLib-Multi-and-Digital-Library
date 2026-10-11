import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { ShieldCheck } from 'lucide-react';
import { platformApi, publicApi } from '@/api/endpoints';
import { PageHeader, Panel } from '@/components/ui';
import { Input, Select } from '@/components/form';
import { titleCase } from '@/lib/format';
import { AUDIT_ACTIONS, AuditTable } from '@/features/staff/AuditTable';
import { useDebounced } from '@/features/staff/useDebounced';

export default function PlatformAuditPage() {
  const [library, setLibrary] = useState('');
  const [action, setAction] = useState('');
  const [outcome, setOutcome] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const [q, setQ, debounced] = useDebounced('');
  const libraries = useQuery({ queryKey: ['public', 'libraries', ''], queryFn: () => publicApi.libraries() });
  const query = useQuery({ queryKey: ['platform', 'audit', library, action, outcome, from, to, debounced, page],
    queryFn: () => platformApi.audit({ libraryId: library ? Number(library) : undefined, action: action || undefined, outcome: outcome || undefined, from: from || undefined, to: to || undefined, q: debounced || undefined, page }),
    placeholderData: keepPreviousData });
  return (
    <>
      <PageHeader title="Audit logs" subtitle="Platform-wide, append-only audit trail. Passwords, tokens, OTPs and secrets are never recorded." icon={<ShieldCheck className="size-5" />} />
      <Panel>
        <div className="mb-4 grid gap-3 sm:grid-cols-3 xl:grid-cols-6">
          <Input aria-label="Search" placeholder="Actor email or record id…" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />
          <Select aria-label="Library" value={library} onChange={(e) => { setLibrary(e.target.value); setPage(0); }}>
            <option value="">All libraries</option>{libraries.data?.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
          </Select>
          <Select aria-label="Action" value={action} onChange={(e) => { setAction(e.target.value); setPage(0); }}>
            <option value="">All actions</option>{AUDIT_ACTIONS.map((a) => <option key={a} value={a}>{titleCase(a)}</option>)}
          </Select>
          <Select aria-label="Outcome" value={outcome} onChange={(e) => { setOutcome(e.target.value); setPage(0); }}>
            <option value="">Any outcome</option><option value="SUCCESS">Success</option><option value="FAILURE">Failure</option>
          </Select>
          <Input type="date" aria-label="From date" value={from} onChange={(e) => { setFrom(e.target.value); setPage(0); }} />
          <Input type="date" aria-label="To date" value={to} onChange={(e) => { setTo(e.target.value); setPage(0); }} />
        </div>
        <AuditTable query={query} onPage={setPage} showLibrary />
      </Panel>
    </>
  );
}
