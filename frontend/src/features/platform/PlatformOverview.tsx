import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, BookCopy, Bot, Building2, Gauge, HandCoins, Repeat2, TrendingUp, UserCheck, Users } from 'lucide-react';
import { platformApi } from '@/api/endpoints';
import { BarsChart, TrendChart } from '@/components/charts';
import { QueryState } from '@/components/states';
import { PageHeader, Panel, Skeleton, StatCard } from '@/components/ui';
import { money, num } from '@/lib/format';

export default function PlatformOverview() {
  const overview = useQuery({ queryKey: ['platform', 'overview'], queryFn: platformApi.overview, refetchInterval: 60_000 });
  const libraries = useQuery({ queryKey: ['platform', 'libraries'], queryFn: platformApi.libraries });
  const trends = useQuery({ queryKey: ['platform', 'trends', 12], queryFn: () => platformApi.trends(12) });
  return (
    <>
      <PageHeader title="Platform overview" subtitle="Live figures across every SmartLib library." icon={<Gauge className="size-5" />} />
      <QueryState query={overview} loading={<div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">{Array.from({ length: 8 }, (_, i) => <Skeleton key={i} className="h-24" />)}</div>}>
        {(o) => (
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <StatCard label="Libraries" value={num(o.libraries)} hint={`${num(o.activeLibraries)} active`} icon={<Building2 className="size-5" />} to="/app/super-admin/libraries" />
            <StatCard label="Members" value={num(o.members)} hint={`+${num(o.newMembers30d)} in 30 days`} icon={<Users className="size-5" />} tone="violet" to="/app/super-admin/users" />
            <StatCard label="Titles / copies" value={`${num(o.titles)} / ${num(o.copies)}`} hint={`${num(o.availableCopies)} copies available`} icon={<BookCopy className="size-5" />} tone="cyan" to="/app/super-admin/catalogue" />
            <StatCard label="Active loans" value={num(o.activeLoans)} hint={`${num(o.totalLoans)} loans all time`} icon={<Repeat2 className="size-5" />} />
            <StatCard label="Overdue loans" value={num(o.overdueLoans)} icon={<AlertTriangle className="size-5" />} tone={o.overdueLoans ? 'red' : 'green'} />
            <StatCard label="Fines collected" value={money(o.finesCollectedPaise)} hint={`${money(o.finesOutstandingPaise)} outstanding`} icon={<HandCoins className="size-5" />} tone="green" />
            <StatCard label="Staff" value={`${num(o.librarians)} librarians · ${num(o.admins)} admins`}
              hint={`${num(o.pendingLibrarianApplications)} librarian · ${num(o.pendingAdminApplications)} admin applications pending`}
              icon={<UserCheck className="size-5" />} tone="amber" to="/app/super-admin/applications" />
            <StatCard label="AI assistant (30 days)" value={`${num(o.aiMessages30d)} chats`}
              hint={o.aiAvgFirstTokenMs != null ? `First token ~${(o.aiAvgFirstTokenMs / 1000).toFixed(1)}s · full ~${((o.aiAvgLatencyMs ?? 0) / 1000).toFixed(1)}s` : 'No conversations yet'} icon={<Bot className="size-5" />} tone="blue" />
          </div>
        )}
      </QueryState>
      <div className="mt-6 grid gap-6 xl:grid-cols-2">
        <Panel title="Platform borrowing trend" icon={<TrendingUp className="size-5" />}><QueryState query={trends}>{(d) => <TrendChart data={d} />}</QueryState></Panel>
        <Panel title="Library performance" icon={<Building2 className="size-5" />} actions={<Link to="/app/super-admin/libraries" className="text-sm font-semibold text-accent-2 hover:underline">Manage</Link>}>
          <QueryState query={libraries}>{(d) => <BarsChart data={d.map((l) => ({ name: l.code, activeLoans: l.activeLoans, members: l.members }))} xKey="name" label="Active loans and members per library"
            bars={[{ key: 'activeLoans', name: 'Active loans', color: '#1688ff' }, { key: 'members', name: 'Members', color: '#39c5ff' }]} />}</QueryState>
        </Panel>
      </div>
    </>
  );
}
