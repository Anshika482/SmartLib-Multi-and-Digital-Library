import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { FileClock } from 'lucide-react';
import { PageHeader, Panel } from '@/components/ui';
import { Input, Select } from '@/components/form';
import { titleCase } from '@/lib/format';
import { AUDIT_ACTIONS, AuditTable } from './AuditTable';
import { useStaff } from './useStaff';
import { useDebounced } from './useDebounced';

export default function AuditPage() {
  const { api, libraryId, libraryName } = useStaff();
  const [action, setAction] = useState('');
  const [outcome, setOutcome] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const [q, setQ, debounced] = useDebounced('');
  const query = useQuery({ queryKey: ['manage', libraryId, 'audit', action, outcome, from, to, debounced, page],
    queryFn: () => api.audit({ action: action || undefined, outcome: outcome || undefined, from: from || undefined, to: to || undefined, q: debounced || undefined, page }),
    placeholderData: keepPreviousData });
  return (
    <>
      <PageHeader title="Audit logs" subtitle={`Append-only record of security and business actions at ${libraryName}. Secrets are never logged.`} icon={<FileClock className="size-5" />} />
      <Panel>
        <div className="mb-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
          <Input aria-label="Search by email or record id" placeholder="Actor email or record id…" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />
          <Select aria-label="Action" value={action} onChange={(e) => { setAction(e.target.value); setPage(0); }}>
            <option value="">All actions</option>
            {AUDIT_ACTIONS.map((a) => <option key={a} value={a}>{titleCase(a)}</option>)}
          </Select>
          <Select aria-label="Outcome" value={outcome} onChange={(e) => { setOutcome(e.target.value); setPage(0); }}>
            <option value="">Any outcome</option><option value="SUCCESS">Success</option><option value="FAILURE">Failure</option>
          </Select>
          <Input type="date" aria-label="From date" value={from} onChange={(e) => { setFrom(e.target.value); setPage(0); }} />
          <Input type="date" aria-label="To date" value={to} onChange={(e) => { setTo(e.target.value); setPage(0); }} />
        </div>
        <AuditTable query={query} onPage={setPage} />
      </Panel>
    </>
  );
}
