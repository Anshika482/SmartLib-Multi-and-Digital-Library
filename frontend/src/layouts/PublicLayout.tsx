import { useState } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router';
import * as Dialog from '@radix-ui/react-dialog';
import { ArrowRight, LayoutDashboard, LogIn, Menu, X } from 'lucide-react';
import { cn } from '@/lib/cn';
import { BackToHome, Logo, LinkButton, Skeleton } from '@/components/ui';
import { roleHome, useSession } from '@/stores/session';
import { UserMenu } from './UserMenu';
import { SiteFooter } from './SiteFooter';

const links = [
  { to: '/catalogue', label: 'Catalogue' },
  { to: '/libraries', label: 'Libraries' },
  { to: '/assistant', label: 'Assistant' },
];

export function Navbar() {
  const { status, user } = useSession();
  const [open, setOpen] = useState(false);
  const location = useLocation();
  const linkClass = ({ isActive }: { isActive: boolean }) =>
    cn('relative px-1 py-2 text-[15px] font-medium transition', isActive ? 'text-fg' : 'text-muted hover:text-fg',
      isActive && 'after:absolute after:inset-x-0 after:-bottom-0.5 after:h-0.5 after:rounded-full after:bg-gradient-to-r after:from-accent after:to-accent-2');
  return (
    <header className="sticky top-0 z-40 border-b border-line/70 bg-ink/70 backdrop-blur-xl">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-3 focus:z-50 focus:rounded-lg focus:bg-accent focus:px-3 focus:py-2">Skip to content</a>
      {/* Full width: logo at the left edge, actions at the right edge, links centred (3-column grid from lg up;
          below lg there isn't room for all of it, so the menu button takes over). */}
      <div className="flex h-[76px] items-center justify-between gap-4 px-4 sm:px-6 lg:grid lg:grid-cols-[1fr_auto_1fr] lg:px-8">
        <div className="flex justify-self-start"><Logo size="md" /></div>
        <nav aria-label="Main" className="hidden items-center gap-10 lg:flex">
          {links.map((l) => <NavLink key={l.to} to={l.to} className={linkClass}>{l.label}</NavLink>)}
        </nav>
        <div className="hidden items-center gap-3 justify-self-end lg:flex">
          {status === 'unknown' ? <Skeleton className="h-10 w-48" /> : user ? (
            <>
              <LinkButton to={roleHome(user.role)} variant="outline" icon={<LayoutDashboard className="size-4" />}>Dashboard</LinkButton>
              <UserMenu compact />
            </>
          ) : (
            <>
              <LinkButton to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} variant="outline" icon={<LogIn className="size-4" />}>Login</LinkButton>
              <LinkButton to="/catalogue">Explore Catalogue <ArrowRight className="size-4" /></LinkButton>
            </>
          )}
        </div>
        <Dialog.Root open={open} onOpenChange={setOpen}>
          <Dialog.Trigger className="btn btn-outline btn-icon lg:hidden" aria-label="Open menu"><Menu className="size-5" /></Dialog.Trigger>
          <Dialog.Portal>
            <Dialog.Overlay className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm" />
            <Dialog.Content className="fixed inset-y-0 right-0 z-50 flex w-[86vw] max-w-sm flex-col gap-6 border-l border-line bg-ink-raised p-5">
              <div className="flex items-center justify-between">
                <Logo size="sm" />
                <Dialog.Close className="btn btn-ghost btn-icon" aria-label="Close menu"><X className="size-5" /></Dialog.Close>
              </div>
              <Dialog.Title className="sr-only">Navigation</Dialog.Title>
              <Dialog.Description className="sr-only">Site navigation</Dialog.Description>
              <nav aria-label="Mobile" className="flex flex-col gap-1">
                {links.map((l) => (
                  <NavLink key={l.to} to={l.to} onClick={() => setOpen(false)}
                    className={({ isActive }) => cn('rounded-xl px-3 py-3 text-base font-medium', isActive ? 'bg-accent/15 text-fg' : 'text-muted')}>
                    {l.label}
                  </NavLink>
                ))}
              </nav>
              <div className="mt-auto flex flex-col gap-2">
                {user ? (
                  <LinkButton to={roleHome(user.role)} onClick={() => setOpen(false)}>Go to dashboard</LinkButton>
                ) : (
                  <>
                    <LinkButton to="/login" variant="outline" onClick={() => setOpen(false)}>Login</LinkButton>
                    <LinkButton to="/register" onClick={() => setOpen(false)}>Create account</LinkButton>
                  </>
                )}
              </div>
            </Dialog.Content>
          </Dialog.Portal>
        </Dialog.Root>
      </div>
    </header>
  );
}

export function PublicLayout() {
  const { pathname } = useLocation();
  return (
    <div className="flex min-h-dvh flex-col">
      <Navbar />
      <main id="main" className="flex-1">
        {pathname !== '/' && (
          <div className="mx-auto -mb-4 max-w-7xl px-4 pt-5 sm:px-6"><BackToHome /></div>
        )}
        <Outlet />
      </main>
      <SiteFooter />
    </div>
  );
}

export { Link };
