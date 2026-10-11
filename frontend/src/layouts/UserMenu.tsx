import * as Dropdown from '@radix-ui/react-dropdown-menu';
import { Link } from 'react-router';
import { Bell, ChevronDown, Home, LayoutDashboard, LogOut, UserRound } from 'lucide-react';
import { Avatar } from '@/components/ui';
import { roleHome, useSession } from '@/stores/session';
import { useLogout } from '@/app/useLogout';
import { titleCase } from '@/lib/format';

export function UserMenu({ compact }: { compact?: boolean }) {
  const user = useSession((s) => s.user);
  const logout = useLogout();
  if (!user) return null;
  const home = roleHome(user.role);
  const item = 'flex cursor-pointer items-center gap-2.5 rounded-lg px-2.5 py-2 text-sm text-muted outline-none data-[highlighted]:bg-white/6 data-[highlighted]:text-fg';
  return (
    <Dropdown.Root>
      <Dropdown.Trigger className="flex items-center gap-2 rounded-xl border border-line bg-white/[0.03] py-1 pl-1 pr-2 hover:border-line-strong" aria-label="Account menu">
        <Avatar name={user.fullName} className="size-8" />
        {!compact && (
          <span className="hidden text-left sm:block">
            <span className="block max-w-36 truncate text-sm font-semibold leading-tight">{user.fullName}</span>
            <span className="block text-[11px] leading-tight text-faint">{titleCase(user.role)}</span>
          </span>
        )}
        <ChevronDown className="size-4 text-faint" aria-hidden />
      </Dropdown.Trigger>
      <Dropdown.Portal>
        <Dropdown.Content align="end" sideOffset={8} className="glass z-50 min-w-56 p-1.5">
          <div className="px-2.5 py-2">
            <p className="truncate text-sm font-semibold">{user.fullName}</p>
            <p className="truncate text-xs text-faint">{user.email}</p>
            {user.library && <p className="mt-1 truncate text-xs text-accent-2">{user.library.name}</p>}
          </div>
          <Dropdown.Separator className="my-1 h-px bg-line" />
          <Dropdown.Item asChild><Link to={home} className={item}><LayoutDashboard className="size-4" />Dashboard</Link></Dropdown.Item>
          <Dropdown.Item asChild><Link to={`${home}/notifications`} className={item}><Bell className="size-4" />Notifications</Link></Dropdown.Item>
          <Dropdown.Item asChild><Link to={`${home}/profile`} className={item}><UserRound className="size-4" />Profile & security</Link></Dropdown.Item>
          <Dropdown.Item asChild><Link to="/" className={item}><Home className="size-4" />SmartLib home</Link></Dropdown.Item>
          <Dropdown.Separator className="my-1 h-px bg-line" />
          <Dropdown.Item onSelect={() => void logout()} className={item}><LogOut className="size-4" />Log out</Dropdown.Item>
        </Dropdown.Content>
      </Dropdown.Portal>
    </Dropdown.Root>
  );
}
