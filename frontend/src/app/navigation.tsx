import type { ReactNode } from 'react';
import {
  Bell, BookCopy, BookMarked, Bot, Building2, ClipboardList, Cog, CreditCard, FileClock, FolderOpen, Gauge, Globe2,
  HandCoins, LayoutDashboard, Megaphone, PieChart, Repeat2, ShieldCheck, UserCheck, UserCog, UserRound, Users,
} from 'lucide-react';

export interface NavItem {
  to: string;
  label: string;
  icon: ReactNode;
  end?: boolean;
  badge?: 'notifications';
}
export interface NavConfig {
  role: 'MEMBER' | 'LIBRARIAN' | 'ADMIN' | 'SUPER_ADMIN';
  base: string;
  title: string;
  items: NavItem[];
}

const i = (C: typeof Bell) => <C className="size-[18px]" aria-hidden />;

export const memberNav: NavConfig = {
  role: 'MEMBER', base: '/app/member', title: 'Member',
  items: [
    { to: '/app/member', label: 'Overview', icon: i(LayoutDashboard), end: true },
    { to: '/app/member/requests', label: 'My Requests', icon: i(ClipboardList) },
    { to: '/app/member/borrowings', label: 'Borrowed Books', icon: i(BookMarked) },
    { to: '/app/member/fines', label: 'Fines', icon: i(HandCoins) },
    { to: '/app/member/payments', label: 'Payments', icon: i(CreditCard) },
    { to: '/app/member/resources', label: 'Digital Resources', icon: i(FolderOpen) },
    { to: '/app/member/notifications', label: 'Notifications', icon: i(Bell), badge: 'notifications' },
    { to: '/app/member/assistant', label: 'AI Assistant', icon: i(Bot) },
    { to: '/app/member/profile', label: 'Profile', icon: i(UserRound) },
  ],
};

const staffItems = (base: string, admin: boolean): NavItem[] => [
  { to: base, label: admin ? 'Library Overview' : 'Overview', icon: i(LayoutDashboard), end: true },
  { to: `${base}/requests`, label: 'Borrow Requests', icon: i(ClipboardList) },
  { to: `${base}/books`, label: 'Books & Copies', icon: i(BookCopy) },
  { to: `${base}/borrowings`, label: 'Borrowings & Returns', icon: i(Repeat2) },
  { to: `${base}/fines`, label: admin ? 'Fines & Payments' : 'Fines', icon: i(HandCoins) },
  { to: `${base}/members`, label: 'Members', icon: i(Users) },
  ...(admin ? [{ to: `${base}/librarians`, label: 'Librarians', icon: i(UserCog) }] : []),
  { to: `${base}/resources`, label: 'Digital Resources', icon: i(FolderOpen) },
  { to: `${base}/announcements`, label: 'Announcements', icon: i(Megaphone) },
  { to: `${base}/reports`, label: 'Reports', icon: i(PieChart) },
  ...(admin ? [
    { to: `${base}/audit`, label: 'Audit Logs', icon: i(FileClock) },
    { to: `${base}/settings`, label: 'Settings', icon: i(Cog) },
  ] : []),
  { to: `${base}/notifications`, label: 'Notifications', icon: i(Bell), badge: 'notifications' as const },
  { to: `${base}/profile`, label: 'Profile', icon: i(UserRound) },
];

export const librarianNav: NavConfig = { role: 'LIBRARIAN', base: '/app/librarian', title: 'Librarian', items: staffItems('/app/librarian', false) };
export const adminNav: NavConfig = { role: 'ADMIN', base: '/app/admin', title: 'Library Admin', items: staffItems('/app/admin', true) };

export const superAdminNav: NavConfig = {
  role: 'SUPER_ADMIN', base: '/app/super-admin', title: 'Platform',
  items: [
    { to: '/app/super-admin', label: 'Platform Overview', icon: i(Gauge), end: true },
    { to: '/app/super-admin/libraries', label: 'Libraries', icon: i(Building2) },
    { to: '/app/super-admin/users', label: 'Users', icon: i(Users) },
    { to: '/app/super-admin/applications', label: 'Admin Applications', icon: i(UserCheck) },
    { to: '/app/super-admin/catalogue', label: 'Global Catalogue', icon: i(Globe2) },
    { to: '/app/super-admin/reports', label: 'Reports & Trends', icon: i(PieChart) },
    { to: '/app/super-admin/audit', label: 'Audit Logs', icon: i(ShieldCheck) },
    { to: '/app/super-admin/config', label: 'Configuration', icon: i(Cog) },
    { to: '/app/super-admin/notifications', label: 'Notifications', icon: i(Bell), badge: 'notifications' },
    { to: '/app/super-admin/profile', label: 'Profile', icon: i(UserRound) },
  ],
};
