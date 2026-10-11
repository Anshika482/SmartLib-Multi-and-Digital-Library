import { useState } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router';
import * as Dialog from '@radix-ui/react-dialog';
import { useQuery } from '@tanstack/react-query';
import { Bell, Building2, Menu, Search, X } from 'lucide-react';
import { cn } from '@/lib/cn';
import { BackToHome, Logo } from '@/components/ui';
import { meApi } from '@/api/endpoints';
import { useSession } from '@/stores/session';
import type { NavConfig } from '@/app/navigation';
import { UserMenu } from './UserMenu';

function useUnread() {
  return useQuery({ queryKey: ['notifications', 'unread'], queryFn: meApi.unreadCount, refetchInterval: 60_000, refetchOnWindowFocus: true });
}

function SidebarNav({ nav, onNavigate }: { nav: NavConfig; onNavigate?: () => void }) {
  const unread = useUnread().data?.count ?? 0;
  return (
    <nav aria-label={`${nav.title} navigation`} className="flex flex-col gap-0.5">
      {nav.items.map((item) => (
        <NavLink key={item.to} to={item.to} end={item.end} onClick={onNavigate}
          className={({ isActive }) => cn('group flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition',
            isActive ? 'bg-gradient-to-r from-accent/25 to-accent/5 text-fg shadow-[inset_2px_0_0_#39c5ff]' : 'text-muted hover:bg-white/[0.04] hover:text-fg')}>
          <span className="text-accent-2/90 group-hover:text-accent-2">{item.icon}</span>
          <span className="flex-1 truncate">{item.label}</span>
          {item.badge === 'notifications' && unread > 0 && (
            <span className="rounded-full bg-accent px-1.5 py-0.5 text-[10px] font-bold text-white" aria-label={`${unread} unread`}>{unread > 99 ? '99+' : unread}</span>
          )}
        </NavLink>
      ))}
    </nav>
  );
}

export function DashboardLayout({ nav }: { nav: NavConfig }) {
  const [open, setOpen] = useState(false);
  const user = useSession((s) => s.user);
  const unread = useUnread().data?.count ?? 0;
  const location = useLocation();
  const context = user?.library?.name ?? (nav.role === 'SUPER_ADMIN' ? 'All libraries' : null);
  const sidebar = (onNavigate?: () => void) => (
    <div className="flex h-full flex-col">
      <div className="px-2 pb-5 pt-1"><Logo size="sm" /></div>
      {context && (
        <div className="mx-1 mb-4 flex items-center gap-2.5 rounded-xl border border-line bg-accent/8 px-3 py-2.5">
          <Building2 className="size-4 shrink-0 text-accent-2" aria-hidden />
          <div className="min-w-0">
            <p className="text-[10px] uppercase tracking-wider text-faint">{nav.title} workspace</p>
            <p className="truncate text-sm font-semibold">{context}</p>
          </div>
        </div>
      )}
      <div className="flex-1 overflow-y-auto pr-1"><SidebarNav nav={nav} onNavigate={onNavigate} /></div>
      <Link to="/catalogue" onClick={onNavigate} className="mt-4 flex items-center gap-2 rounded-xl border border-line px-3 py-2.5 text-sm text-muted hover:border-line-strong hover:text-fg">
        <Search className="size-4" aria-hidden />Browse public catalogue
      </Link>
    </div>
  );
  return (
    <div className="min-h-dvh lg:grid lg:grid-cols-[272px_1fr]">
      <aside className="sticky top-0 hidden h-dvh border-r border-line/70 bg-ink-raised/70 p-4 backdrop-blur-xl lg:block">{sidebar()}</aside>
      <div className="min-w-0">
        <header className="sticky top-0 z-30 flex h-16 items-center gap-3 border-b border-line/70 bg-ink/75 px-4 backdrop-blur-xl sm:px-6">
          <Dialog.Root open={open} onOpenChange={setOpen}>
            <Dialog.Trigger className="btn btn-outline btn-icon lg:hidden" aria-label="Open navigation"><Menu className="size-5" /></Dialog.Trigger>
            <Dialog.Portal>
              <Dialog.Overlay className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm" />
              <Dialog.Content className="fixed inset-y-0 left-0 z-50 w-[82vw] max-w-xs border-r border-line bg-ink-raised p-4">
                <Dialog.Title className="sr-only">Navigation</Dialog.Title>
                <Dialog.Description className="sr-only">Dashboard navigation</Dialog.Description>
                <Dialog.Close className="btn btn-ghost btn-icon absolute right-3 top-3" aria-label="Close navigation"><X className="size-5" /></Dialog.Close>
                {sidebar(() => setOpen(false))}
              </Dialog.Content>
            </Dialog.Portal>
          </Dialog.Root>
          <BackToHome compact />
          <span className="h-5 w-px shrink-0 bg-line" aria-hidden />
          <p className="truncate text-sm text-muted">
            <span className="hidden sm:inline">{nav.title} · </span>
            <span className="font-semibold text-fg">Welcome, {user?.fullName.split(' ')[0]}</span>
          </p>
          <div className="ml-auto flex items-center gap-2">
            <Link to={`${nav.base}/notifications`} className="btn btn-ghost btn-icon relative" aria-label={`Notifications${unread ? `, ${unread} unread` : ''}`}>
              <Bell className="size-5" />
              {unread > 0 && <span className="absolute right-1.5 top-1.5 size-2.5 rounded-full bg-accent-2 ring-2 ring-ink" aria-hidden />}
            </Link>
            <UserMenu />
          </div>
        </header>
        <main id="main" key={location.pathname.split('/').slice(0, 4).join('/')} className="mx-auto w-full max-w-[1400px] px-4 py-6 sm:px-6 lg:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
