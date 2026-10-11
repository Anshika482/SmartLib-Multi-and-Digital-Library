import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, BookCopy, BookOpenCheck, ClipboardList, HandCoins, History, PackageCheck, Repeat2, TrendingUp, Users } from 'lucide-react';
import { BookCover } from '@/components/book';
import { TrendChart } from '@/components/charts';
import { EmptyState, QueryState } from '@/components/states';
import { Panel, Skeleton, StatCard } from '@/components/ui';
import { StatusBadge } from '@/components/status';
import { date, money, num, relative, titleCase } from '@/lib/format';
import { RequestActions } from './RequestActions';
import { useStaff } from './useStaff';

export default function StaffOverview() {
  const { api, base, libraryId, isAdmin } = useStaff();
  const dash = useQuery({ queryKey: ['manage', libraryId, 'dashboard'], queryFn: api.dashboard, refetchInterval: 60_000 });
  return (
    <QueryState query={dash} loading={<div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">{Array.from({ length: 8 }, (_, i) => <Skeleton key={i} className="h-24" />)}</div>}>
      {(d) => (
        <div className="space-y-6">
          <div className="glass relative overflow-hidden p-6">
            <div className="pointer-events-none absolute -right-10 -top-16 size-64 rounded-full bg-accent/20 blur-3xl" aria-hidden />
            <p className="eyebrow">{isAdmin ? 'Library administration' : 'Librarian workspace'}</p>
            <h1 className="mt-1 text-2xl font-bold sm:text-3xl">{d.library.name}</h1>
            <p className="mt-1 text-sm text-muted">{d.library.city} · Code {d.library.code} · {num(d.summary.loansThisMonth)} books issued this month</p>
          </div>
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <StatCard label="Titles in catalogue" value={num(d.summary.titles)} hint={`${num(d.summary.copies)} copies in circulation`} icon={<BookCopy className="size-5" />} to={`${base}/books`} />
            <StatCard label="Available copies" value={num(d.summary.availableCopies)} hint={`${num(d.summary.reservedCopies)} reserved · ${num(d.summary.lostOrDamaged)} lost/damaged`} icon={<BookOpenCheck className="size-5" />} tone="green" />
            <StatCard label="Pending requests" value={num(d.summary.pendingRequests)} hint={`${num(d.summary.readyForPickup)} awaiting pickup`} icon={<ClipboardList className="size-5" />} tone="amber" to={`${base}/requests`} />
            <StatCard label="Overdue loans" value={num(d.summary.overdueLoans)} hint={`${num(d.summary.activeLoans)} active · ${num(d.summary.dueSoon)} due soon`} icon={<AlertTriangle className="size-5" />} tone={d.summary.overdueLoans ? 'red' : 'green'} to={`${base}/borrowings?filter=OVERDUE`} />
            <StatCard label="Borrowed copies" value={num(d.summary.borrowedCopies)} icon={<Repeat2 className="size-5" />} tone="cyan" to={`${base}/borrowings`} />
            <StatCard label="Members" value={num(d.summary.totalMembers)} hint={`${num(d.summary.activeMembers90d)} active in 90 days`} icon={<Users className="size-5" />} tone="violet" to={`${base}/members`} />
            <StatCard label="Fines outstanding" value={money(d.summary.finesOutstandingPaise)} icon={<HandCoins className="size-5" />} tone="amber" to={`${base}/fines`} />
            <StatCard label="Collected (30 days)" value={money(d.summary.finesCollected30dPaise)} hint={`${money(d.summary.finesCollectedTotalPaise)} all time`} icon={<PackageCheck className="size-5" />} tone="green" />
          </div>
          {isAdmin && d.summary.pendingLibrarianApplications > 0 && (
            <Link to={`${base}/librarians`} className="flex items-center gap-3 rounded-xl border border-warning/30 bg-warning/10 p-4 text-sm">
              <Users className="size-5 text-warning" /><span><b>{d.summary.pendingLibrarianApplications}</b> librarian application(s) waiting for your review.</span>
            </Link>
          )}
          <Panel title="Pending requests" icon={<ClipboardList className="size-5" />} actions={<Link to={`${base}/requests`} className="text-sm font-semibold text-accent-2 hover:underline">View all</Link>}>
            {d.pendingRequests.length === 0 ? <EmptyState title="No pending requests" description="New requests to this library appear here." /> : (
              <ul className="divide-y divide-line">
                {d.pendingRequests.map((r) => (
                  <li key={r.id} className="flex flex-wrap items-center gap-3 py-3">
                    <BookCover book={r.book} className="w-10 shrink-0" rounded="rounded" />
                    <div className="min-w-0 flex-1">
                      <p className="line-clamp-1 font-medium">{r.book.title}</p>
                      <p className="text-xs text-faint">{r.member?.fullName} · {relative(r.createdAt)} · {r.availableCopies} available</p>
                    </div>
                    <StatusBadge status={r.status} />
                    <RequestActions request={r} />
                  </li>
                ))}
              </ul>
            )}
          </Panel>
          <div className="grid gap-6 xl:grid-cols-2">
            <Panel title="Borrowing trend" icon={<TrendingUp className="size-5" />}><TrendChart data={d.trends} /></Panel>
            <Panel title="Overdue" icon={<AlertTriangle className="size-5" />} actions={<Link to={`${base}/borrowings?filter=OVERDUE`} className="text-sm font-semibold text-accent-2 hover:underline">View all</Link>}>
              {d.overdue.length === 0 ? <EmptyState title="Nothing overdue" /> : (
                <ul className="space-y-2">
                  {d.overdue.map((l) => (
                    <li key={l.id} className="flex items-center justify-between gap-2 rounded-xl border border-line bg-ink/40 p-3 text-sm">
                      <span className="min-w-0"><span className="line-clamp-1 font-medium">{l.book.title}</span><span className="text-xs text-faint">{l.member?.fullName} · due {date(l.dueDate)}</span></span>
                      <span className="shrink-0 text-xs font-semibold text-danger">{l.overdueDays} days · {money(l.estimatedFinePaise)}</span>
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
          </div>
          <Panel title="Recent activity" icon={<History className="size-5" />}>
            {d.recentActivity.length === 0 ? <p className="text-sm text-muted">No activity yet.</p> : (
              <ul className="space-y-2.5">
                {d.recentActivity.map((a) => (
                  <li key={a.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
                    <span className="w-28 shrink-0 text-xs text-faint">{relative(a.occurredAt)}</span>
                    <span className="font-medium">{titleCase(a.action)}</span>
                    <span className="text-xs text-muted">{a.actorEmail ?? 'System'}</span>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        </div>
      )}
    </QueryState>
  );
}
