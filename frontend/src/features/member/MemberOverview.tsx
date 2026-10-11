import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, ArrowRight, Bell, BookMarked, Bot, CalendarClock, ClipboardList, HandCoins, PackageCheck, Search } from 'lucide-react';
import { meApi } from '@/api/endpoints';
import { useSession } from '@/stores/session';
import { BookCover } from '@/components/book';
import { EmptyState, QueryState } from '@/components/states';
import { LinkButton, Panel, Skeleton, StatCard } from '@/components/ui';
import { LoanStatusBadge, StatusBadge } from '@/components/status';
import { date, money, relative } from '@/lib/format';

export default function MemberOverview() {
  const user = useSession((s) => s.user);
  const dash = useQuery({ queryKey: ['me', 'dashboard'], queryFn: meApi.dashboard });
  return (
    <div className="space-y-6">
      <div className="glass relative overflow-hidden p-6">
        <div className="pointer-events-none absolute -right-16 -top-20 size-72 rounded-full bg-accent/20 blur-3xl" aria-hidden />
        <p className="eyebrow">Member dashboard</p>
        <h1 className="mt-1 text-2xl font-bold sm:text-3xl">Welcome back, {user?.fullName.split(' ')[0]}!</h1>
        <p className="mt-1 text-sm text-muted">Keep exploring. Great books await you.</p>
        <div className="mt-4 flex flex-wrap gap-2">
          <LinkButton to="/catalogue" icon={<Search className="size-4" />}>Find a book</LinkButton>
          <LinkButton to="/app/member/assistant" variant="outline" icon={<Bot className="size-4" />}>Ask the AI assistant</LinkButton>
        </div>
      </div>

      <QueryState query={dash} loading={<div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-24" />)}</div>}>
        {(d) => (
          <>
            {d.overdue > 0 && (
              <Link to="/app/member/borrowings?filter=OVERDUE" className="flex items-center gap-3 rounded-xl border border-danger/35 bg-danger/10 p-4 text-sm">
                <AlertTriangle className="size-5 shrink-0 text-danger" />
                <span className="flex-1"><b>{d.overdue}</b> {d.overdue === 1 ? 'book is' : 'books are'} overdue. Please return {d.overdue === 1 ? 'it' : 'them'} to avoid further fines.</span>
                <ArrowRight className="size-4" />
              </Link>
            )}
            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
              <StatCard label="Borrowed books" value={d.activeLoans} hint={d.dueSoon ? `${d.dueSoon} due soon` : 'None due soon'} icon={<BookMarked className="size-5" />} to="/app/member/borrowings" />
              <StatCard label="Pending requests" value={d.pendingRequests} hint={d.readyForPickup ? `${d.readyForPickup} ready for pickup` : undefined} icon={<ClipboardList className="size-5" />} tone="amber" to="/app/member/requests" />
              <StatCard label="Ready for pickup" value={d.readyForPickup} icon={<PackageCheck className="size-5" />} tone="cyan" to="/app/member/requests" />
              <StatCard label="Outstanding fines" value={money(d.unpaidFinesPaise)} hint={`${d.unpaidFinesCount} unpaid`} icon={<HandCoins className="size-5" />} tone={d.unpaidFinesPaise > 0 ? 'red' : 'green'} to="/app/member/fines" />
            </div>
            <div className="grid gap-6 xl:grid-cols-[1.4fr_1fr]">
              <Panel title="My borrowed books" icon={<BookMarked className="size-5" />} actions={<Link to="/app/member/borrowings" className="text-sm font-semibold text-accent-2 hover:underline">View all</Link>}>
                {d.loans.length === 0 ? (
                  <EmptyState title="No books on loan" description="Browse the catalogue and request a book from any library." action={<LinkButton to="/catalogue" size="sm">Browse catalogue</LinkButton>} />
                ) : (
                  <ul className="space-y-3">
                    {d.loans.map((l) => (
                      <li key={l.id} className="flex items-center gap-3 rounded-xl border border-line bg-ink/40 p-3">
                        <BookCover book={l.book} className="w-12 shrink-0" rounded="rounded" />
                        <div className="min-w-0 flex-1">
                          <Link to={`/catalogue/${l.book.id}`} className="line-clamp-1 font-semibold hover:text-accent-2">{l.book.title}</Link>
                          <p className="text-xs text-muted">{l.library.name}</p>
                          <p className="mt-0.5 flex items-center gap-1 text-xs text-faint"><CalendarClock className="size-3.5" />Due {date(l.dueDate)}{l.overdue && <span className="text-danger"> · {l.overdueDays} days overdue</span>}</p>
                        </div>
                        <LoanStatusBadge loan={l} />
                      </li>
                    ))}
                  </ul>
                )}
              </Panel>
              <div className="space-y-6">
                <Panel title="Active requests" icon={<ClipboardList className="size-5" />} actions={<Link to="/app/member/requests" className="text-sm font-semibold text-accent-2 hover:underline">View all</Link>}>
                  {d.requests.length === 0 ? <p className="text-sm text-muted">No pending requests.</p> : (
                    <ul className="space-y-2">
                      {d.requests.map((r) => (
                        <li key={r.id} className="flex items-center justify-between gap-2 text-sm">
                          <span className="min-w-0"><span className="line-clamp-1 font-medium">{r.book.title}</span><span className="text-xs text-faint">{r.library.name}</span></span>
                          <StatusBadge status={r.status} />
                        </li>
                      ))}
                    </ul>
                  )}
                </Panel>
                <Panel title="Recent notifications" icon={<Bell className="size-5" />} actions={<Link to="/app/member/notifications" className="text-sm font-semibold text-accent-2 hover:underline">View all</Link>}>
                  {d.notifications.length === 0 ? <p className="text-sm text-muted">You're all caught up.</p> : (
                    <ul className="space-y-3">
                      {d.notifications.map((n) => (
                        <li key={n.id} className="flex gap-2.5 text-sm">
                          <span className={`mt-1.5 size-2 shrink-0 rounded-full ${n.read ? 'bg-white/15' : 'bg-accent-2'}`} aria-label={n.read ? 'Read' : 'Unread'} />
                          <span className="min-w-0"><span className="block font-medium">{n.title}</span><span className="text-xs text-faint">{relative(n.createdAt)}</span></span>
                        </li>
                      ))}
                    </ul>
                  )}
                </Panel>
              </div>
            </div>
          </>
        )}
      </QueryState>
    </div>
  );
}
