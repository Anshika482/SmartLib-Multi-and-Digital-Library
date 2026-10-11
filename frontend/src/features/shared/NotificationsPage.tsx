import { useState } from 'react';
import { useNavigate } from 'react-router';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bell, BellRing, CheckCheck, CreditCard, HandCoins, Megaphone, PackageCheck, ShieldCheck, UserCheck } from 'lucide-react';
import { meApi } from '@/api/endpoints';
import type { NotificationView } from '@/api/types';
import { EmptyState, QueryState } from '@/components/states';
import { Pagination } from '@/components/table';
import { Button, PageHeader, Segmented } from '@/components/ui';
import { cn } from '@/lib/cn';
import { dateTime, relative } from '@/lib/format';

const icons: Record<string, typeof Bell> = {
  FINE_CREATED: HandCoins, FINE_WAIVED: HandCoins, PAYMENT_SUCCESS: CreditCard, REQUEST_APPROVED: PackageCheck, ANNOUNCEMENT: Megaphone,
  LIBRARIAN_APPROVED: UserCheck, LIBRARIAN_APPLICATION: UserCheck, WELCOME: ShieldCheck, OVERDUE: BellRing, DUE_SOON: BellRing, DUE_TODAY: BellRing,
};

export default function NotificationsPage() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [page, setPage] = useState(0);
  const list = useQuery({ queryKey: ['notifications', 'list', unreadOnly, page], queryFn: () => meApi.notifications({ unreadOnly, page, size: 15 }), placeholderData: keepPreviousData });
  const refresh = () => qc.invalidateQueries({ queryKey: ['notifications'] });
  const markAll = useMutation({ mutationFn: meApi.markAllRead, onSuccess: refresh });
  const markOne = useMutation({ mutationFn: meApi.markRead, onSuccess: refresh });
  const open = (n: NotificationView) => {
    if (!n.read) markOne.mutate(n.id);
    if (n.actionUrl) navigate(n.actionUrl);
  };
  return (
    <>
      <PageHeader title="Notifications" subtitle="Requests, loans, fines, payments and library announcements." icon={<Bell className="size-5" />}
        actions={<Button variant="outline" icon={<CheckCheck className="size-4" />} loading={markAll.isPending} onClick={() => markAll.mutate()}>Mark all as read</Button>} />
      <Segmented label="Filter notifications" value={unreadOnly ? 'unread' : 'all'} onChange={(v) => { setUnreadOnly(v === 'unread'); setPage(0); }} className="mb-5"
        options={[{ value: 'all', label: 'All' }, { value: 'unread', label: 'Unread' }]} />
      <div className="glass p-2 sm:p-3">
        <QueryState query={list} isEmpty={(d) => d.content.length === 0} empty={<EmptyState icon={<Bell className="size-6" />} title={unreadOnly ? "You're all caught up" : 'No notifications yet'} />}>
          {(d) => (
            <>
              <ul className="divide-y divide-line">
                {d.content.map((n) => {
                  const Icon = icons[n.type] ?? Bell;
                  return (
                    <li key={n.id}>
                      <button type="button" onClick={() => open(n)} className={cn('flex w-full gap-3 rounded-xl px-3 py-3.5 text-left transition hover:bg-white/[0.03]', !n.read && 'bg-accent/[0.06]')}>
                        <span className={cn('flex size-10 shrink-0 items-center justify-center rounded-xl border', n.read ? 'border-line text-faint' : 'border-accent/40 bg-accent/15 text-accent-2')}><Icon className="size-5" /></span>
                        <span className="min-w-0 flex-1">
                          <span className="flex items-start justify-between gap-2">
                            <span className={cn('text-sm', n.read ? 'text-muted' : 'font-semibold text-fg')}>{n.title}</span>
                            <time dateTime={n.createdAt} title={dateTime(n.createdAt)} className="shrink-0 text-xs text-faint">{relative(n.createdAt)}</time>
                          </span>
                          {n.body && <span className="mt-0.5 block text-sm text-muted">{n.body}</span>}
                          {!n.read && <span className="sr-only">Unread</span>}
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
              <div className="px-2 pb-2"><Pagination page={d.page} totalPages={d.totalPages} size={d.size} totalElements={d.totalElements} onChange={setPage} label="notifications" /></div>
            </>
          )}
        </QueryState>
      </div>
    </>
  );
}
