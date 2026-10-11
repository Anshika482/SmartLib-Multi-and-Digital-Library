import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Download, PieChart, TrendingUp, Trophy } from 'lucide-react';
import { platformApi } from '@/api/endpoints';
import { download, isApiError } from '@/api/http';
import { BookCover } from '@/components/book';
import { BarsChart, TrendChart } from '@/components/charts';
import { EmptyState, QueryState } from '@/components/states';
import { DataTable } from '@/components/table';
import { Button, PageHeader, Panel, Segmented } from '@/components/ui';
import { money, num } from '@/lib/format';

export default function PlatformReportsPage() {
  const [months, setMonths] = useState<'6' | '12' | '24'>('12');
  const trends = useQuery({ queryKey: ['platform', 'trends', months], queryFn: () => platformApi.trends(Number(months)) });
  const popular = useQuery({ queryKey: ['platform', 'popular'], queryFn: () => platformApi.popular(90) });
  const libraries = useQuery({ queryKey: ['platform', 'libraries'], queryFn: platformApi.libraries });
  return (
    <>
      <PageHeader title="Reports & trends" subtitle="Platform-wide figures computed from live records." icon={<PieChart className="size-5" />}
        actions={<Button variant="outline" icon={<Download className="size-4" />} onClick={async () => {
          try { await download('/api/platform/analytics/export.csv?type=libraries', 'smartlib-libraries.csv'); } catch (e) { toast.error(isApiError(e) ? e.message : 'Export failed'); }
        }}>Libraries CSV</Button>} />
      <div className="grid gap-6 xl:grid-cols-2">
        <Panel title="Borrowing & returns" icon={<TrendingUp className="size-5" />} actions={<Segmented label="Months" value={months} onChange={setMonths} options={[{ value: '6', label: '6 mo' }, { value: '12', label: '12 mo' }, { value: '24', label: '24 mo' }]} />}>
          <QueryState query={trends}>{(d) => <TrendChart data={d} />}</QueryState>
        </Panel>
        <Panel title="New members & fines per month">
          <QueryState query={trends}>{(d) => <BarsChart data={d.map((p) => ({ month: p.month.slice(2), members: p.newMembers, fines: Math.round(p.finesCollectedPaise / 100) }))} xKey="month"
            label="New members and fines collected (₹) per month" bars={[{ key: 'members', name: 'New members', color: '#a78bfa' }, { key: 'fines', name: 'Fines collected (₹)', color: '#34d399' }]} />}</QueryState>
        </Panel>
        <Panel title="Library comparison" className="xl:col-span-2">
          <QueryState query={libraries}>{(d) => (
            <DataTable caption="Library comparison" rows={d} rowKey={(l) => l.id} columns={[
              { key: 'name', header: 'Library', primary: true, sortValue: (l) => l.name, cell: (l) => <span className="font-medium text-fg">{l.name}</span> },
              { key: 'titles', header: 'Titles', sortValue: (l) => l.titles, cell: (l) => num(l.titles) },
              { key: 'loans', header: 'Active loans', sortValue: (l) => l.activeLoans, cell: (l) => num(l.activeLoans) },
              { key: 'overdue', header: 'Overdue', sortValue: (l) => l.overdueLoans, cell: (l) => num(l.overdueLoans) },
              { key: 'members', header: 'Members', sortValue: (l) => l.members, cell: (l) => num(l.members) },
              { key: 'fines', header: 'Fines collected', sortValue: (l) => l.finesCollectedPaise, cell: (l) => money(l.finesCollectedPaise) },
              { key: 'staff', header: 'Staff', sortValue: (l) => l.staff, cell: (l) => num(l.staff) },
            ]} />
          )}</QueryState>
        </Panel>
        <Panel title="Most borrowed (90 days)" icon={<Trophy className="size-5" />} className="xl:col-span-2">
          <QueryState query={popular} isEmpty={(d) => d.length === 0} empty={<EmptyState title="No loans in the last 90 days" />}>
            {(d) => (
              <ol className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
                {d.map((p, i) => (
                  <li key={p.book.id} className="flex items-center gap-3 rounded-xl border border-line bg-ink/40 p-3">
                    <span className="w-6 text-center text-sm font-bold text-faint">{i + 1}</span>
                    <BookCover book={p.book} className="w-9 shrink-0" rounded="rounded" />
                    <span className="min-w-0 flex-1"><span className="line-clamp-1 text-sm font-medium">{p.book.title}</span><span className="text-xs text-faint">{p.loans} loans</span></span>
                  </li>
                ))}
              </ol>
            )}
          </QueryState>
        </Panel>
      </div>
    </>
  );
}
