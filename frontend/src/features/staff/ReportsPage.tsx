import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Download, PieChart, TrendingUp, Trophy } from 'lucide-react';
import { download, isApiError } from '@/api/http';
import { BookCover } from '@/components/book';
import { BarsChart, TrendChart } from '@/components/charts';
import { EmptyState, QueryState } from '@/components/states';
import { Button, PageHeader, Panel, Segmented, StatCard } from '@/components/ui';
import { money, num } from '@/lib/format';
import { useStaff } from './useStaff';
import { BookCopy, Repeat2, Users, AlertTriangle } from 'lucide-react';

export default function ReportsPage() {
  const { api, libraryId } = useStaff();
  const [months, setMonths] = useState<'6' | '12'>('6');
  const [days, setDays] = useState<'30' | '90' | '365'>('90');
  const summary = useQuery({ queryKey: ['manage', libraryId, 'summary'], queryFn: api.summary });
  const trends = useQuery({ queryKey: ['manage', libraryId, 'trends', months], queryFn: () => api.trends(Number(months)) });
  const popular = useQuery({ queryKey: ['manage', libraryId, 'popular', days], queryFn: () => api.popular(Number(days)) });
  const categories = useQuery({ queryKey: ['manage', libraryId, 'categories'], queryFn: api.categories });
  const exportCsv = async (type: string) => {
    try {
      await download(api.exportUrl(type), `smartlib-${type}.csv`);
    } catch (e) {
      toast.error(isApiError(e) ? e.message : 'Export failed');
    }
  };
  const max = Math.max(1, ...(popular.data ?? []).map((p) => p.loans));
  return (
    <>
      <PageHeader title="Reports & analytics" subtitle="Live figures computed from your library's records." icon={<PieChart className="size-5" />}
        actions={<div className="flex flex-wrap gap-2">{['loans', 'overdue', 'fines', 'popular'].map((t) => <Button key={t} size="sm" variant="outline" icon={<Download className="size-3.5" />} onClick={() => exportCsv(t)}>{t[0].toUpperCase() + t.slice(1)} CSV</Button>)}</div>} />
      {summary.data && (
        <div className="mb-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <StatCard label="Copies in circulation" value={num(summary.data.copies)} hint={`${num(summary.data.availableCopies)} available`} icon={<BookCopy className="size-5" />} />
          <StatCard label="Active loans" value={num(summary.data.activeLoans)} hint={`${num(summary.data.loansThisMonth)} issued this month`} icon={<Repeat2 className="size-5" />} tone="cyan" />
          <StatCard label="Overdue" value={num(summary.data.overdueLoans)} icon={<AlertTriangle className="size-5" />} tone={summary.data.overdueLoans ? 'red' : 'green'} />
          <StatCard label="Active members (90d)" value={num(summary.data.activeMembers90d)} hint={`Fines collected: ${money(summary.data.finesCollectedTotalPaise)}`} icon={<Users className="size-5" />} tone="violet" />
        </div>
      )}
      <div className="grid gap-6 xl:grid-cols-2">
        <Panel title="Borrowing trend" icon={<TrendingUp className="size-5" />} actions={<Segmented label="Months" value={months} onChange={setMonths} options={[{ value: '6', label: '6 mo' }, { value: '12', label: '12 mo' }]} />}>
          <QueryState query={trends}>{(d) => <TrendChart data={d} />}</QueryState>
        </Panel>
        <Panel title="Categories" icon={<PieChart className="size-5" />}>
          <QueryState query={categories}>{(d) => <BarsChart data={d} xKey="category" label="Titles and loans by category" bars={[{ key: 'titles', name: 'Titles', color: '#1688ff' }, { key: 'loans90d', name: 'Loans (90 days)', color: '#39c5ff' }]} />}</QueryState>
        </Panel>
        <Panel title="Most borrowed" icon={<Trophy className="size-5" />} className="xl:col-span-2"
          actions={<Segmented label="Period" value={days} onChange={setDays} options={[{ value: '30', label: '30 days' }, { value: '90', label: '90 days' }, { value: '365', label: '1 year' }]} />}>
          <QueryState query={popular} isEmpty={(d) => d.length === 0} empty={<EmptyState title="No loans in this period" />}>
            {(d) => (
              <ol className="grid gap-3 md:grid-cols-2">
                {d.map((p, i) => (
                  <li key={p.book.id} className="flex items-center gap-3 rounded-xl border border-line bg-ink/40 p-3">
                    <span className="w-6 text-center text-sm font-bold text-faint">{i + 1}</span>
                    <BookCover book={p.book} className="w-9 shrink-0" rounded="rounded" />
                    <div className="min-w-0 flex-1">
                      <p className="line-clamp-1 text-sm font-medium">{p.book.title}</p>
                      <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-white/5"><div className="h-full rounded-full bg-gradient-to-r from-accent to-accent-2" style={{ width: `${(p.loans / max) * 100}%` }} /></div>
                    </div>
                    <span className="text-sm font-semibold">{p.loans}</span>
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
