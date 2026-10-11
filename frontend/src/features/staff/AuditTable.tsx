import { useState } from 'react';
import type { UseQueryResult } from '@tanstack/react-query';
import { ChevronDown } from 'lucide-react';
import type { AuditEntry, Page } from '@/api/types';
import { EmptyState, QueryState } from '@/components/states';
import { Pagination } from '@/components/table';
import { StatusBadge } from '@/components/status';
import { dateTime, titleCase } from '@/lib/format';

export const AUDIT_ACTIONS = [
  'LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'LOGIN_BLOCKED', 'USER_REGISTERED', 'LIBRARIAN_APPLIED', 'EMAIL_VERIFIED', 'OTP_FAILED', 'PASSWORD_RESET',
  'PASSWORD_CHANGED', 'LIBRARIAN_APPROVED', 'LIBRARIAN_REJECTED', 'STAFF_REVOKED', 'ADMIN_INVITED', 'USER_STATUS_CHANGED', 'MEMBERSHIP_STATUS_CHANGED',
  'LIBRARY_CREATED', 'LIBRARY_UPDATED', 'POLICY_UPDATED', 'BOOK_CREATED', 'BOOK_UPDATED', 'HOLDING_ADDED', 'HOLDING_WITHDRAWN', 'COPIES_ADDED',
  'COPY_STATUS_CHANGED', 'REQUEST_CREATED', 'REQUEST_APPROVED', 'REQUEST_REJECTED', 'REQUEST_EXPIRED', 'BOOK_ISSUED', 'BOOK_RETURNED',
  'FINE_CREATED', 'FINE_WAIVED', 'PAYMENT_ORDER_CREATED', 'PAYMENT_VERIFIED', 'PAYMENT_VERIFICATION_FAILED', 'RESOURCE_CREATED',
  'ANNOUNCEMENT_SENT', 'SETTINGS_UPDATED',
];

export function AuditTable({ query, onPage, showLibrary }: { query: UseQueryResult<Page<AuditEntry>>; onPage: (p: number) => void; showLibrary?: boolean }) {
  const [open, setOpen] = useState<number | null>(null);
  return (
    <QueryState query={query} isEmpty={(d) => d.content.length === 0} empty={<EmptyState title="No audit entries match" />}>
      {(d) => (
        <>
          <ul className="divide-y divide-line">
            {d.content.map((a) => (
              <li key={a.id} className="py-3">
                <button type="button" className="flex w-full flex-wrap items-center gap-x-4 gap-y-1 text-left" aria-expanded={open === a.id} onClick={() => setOpen(open === a.id ? null : a.id)}>
                  <span className="w-40 shrink-0 text-xs text-faint">{dateTime(a.occurredAt)}</span>
                  <span className="min-w-40 flex-1 text-sm font-medium text-fg">{titleCase(a.action)}</span>
                  <span className="text-xs text-muted">{a.actorEmail ?? (a.actorRole === 'SYSTEM' ? 'System' : 'Anonymous')}{a.actorRole && a.actorRole !== 'SYSTEM' ? ` · ${titleCase(a.actorRole)}` : ''}</span>
                  {showLibrary && <span className="text-xs text-faint">{a.libraryName ?? 'Platform'}</span>}
                  <StatusBadge status={a.outcome} />
                  <ChevronDown className={`size-4 text-faint transition ${open === a.id ? 'rotate-180' : ''}`} aria-hidden />
                </button>
                {open === a.id && (
                  <dl className="mt-3 grid gap-2 rounded-xl border border-line bg-ink/50 p-3 text-xs sm:grid-cols-2">
                    <div><dt className="text-faint">Target</dt><dd>{a.entityType ?? '—'} {a.entityId ?? ''}</dd></div>
                    <div><dt className="text-faint">IP address</dt><dd>{a.ip ?? '—'}</dd></div>
                    <div><dt className="text-faint">Request ID</dt><dd className="font-mono">{a.requestId ?? '—'}</dd></div>
                    <div className="sm:col-span-2"><dt className="text-faint">Details</dt><dd><pre className="mt-1 whitespace-pre-wrap break-all font-mono text-[11px] text-muted">{a.details ? JSON.stringify(JSON.parse(a.details), null, 2) : '—'}</pre></dd></div>
                  </dl>
                )}
              </li>
            ))}
          </ul>
          <Pagination page={d.page} totalPages={d.totalPages} size={d.size} totalElements={d.totalElements} onChange={onPage} label="entries" />
        </>
      )}
    </QueryState>
  );
}
