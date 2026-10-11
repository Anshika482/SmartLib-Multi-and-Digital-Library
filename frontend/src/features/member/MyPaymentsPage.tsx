import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { CreditCard } from 'lucide-react';
import { meApi } from '@/api/endpoints';
import { EmptyState, QueryState } from '@/components/states';
import { DataTable, Pagination } from '@/components/table';
import { Badge, PageHeader } from '@/components/ui';
import { StatusBadge } from '@/components/status';
import { dateTime, money, titleCase } from '@/lib/format';

export default function MyPaymentsPage() {
  const [page, setPage] = useState(0);
  const payments = useQuery({ queryKey: ['me', 'payments', page], queryFn: () => meApi.payments(page), placeholderData: keepPreviousData });
  return (
    <>
      <PageHeader title="Payment history" subtitle="Every payment attempt and its server-verified result." icon={<CreditCard className="size-5" />} />
      <div className="glass p-4">
        <QueryState query={payments} isEmpty={(d) => d.content.length === 0} empty={<EmptyState title="No payments yet" />}>
          {(d) => (
            <>
              <DataTable caption="Payments" rows={d.content} rowKey={(p) => p.id} columns={[
                { key: 'date', header: 'Date', primary: true, cell: (p) => dateTime(p.verifiedAt ?? p.createdAt) },
                { key: 'fine', header: 'For', cell: (p) => <span>{titleCase(p.fineType)} fine{p.book ? ` · ${p.book.title}` : ''}</span> },
                { key: 'library', header: 'Library', cell: (p) => p.library.name },
                { key: 'amount', header: 'Amount', align: 'right', cell: (p) => <span className="font-semibold text-fg">{money(p.amountPaise)}</span> },
                { key: 'method', header: 'Method', cell: (p) => <span className="inline-flex items-center gap-1.5">{p.method ? titleCase(p.method) : '—'}{p.provider === 'DEMO' && <Badge tone="amber">Demo</Badge>}</span> },
                { key: 'ref', header: 'Payment ID', cell: (p) => <span className="font-mono text-xs">{p.providerPaymentId ?? p.providerOrderId}</span> },
                { key: 'status', header: 'Status', cell: (p) => <StatusBadge status={p.status} /> },
              ]} />
              <Pagination page={d.page} totalPages={d.totalPages} size={d.size} totalElements={d.totalElements} onChange={setPage} label="payments" />
            </>
          )}
        </QueryState>
      </div>
    </>
  );
}
