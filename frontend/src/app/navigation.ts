import type { Role } from '@/types/api';

/**
 * What each role is offered in the application.
 *
 * <p><b>This decides what is shown, never what is allowed.</b> Hiding a link is
 * a courtesy - it keeps somebody from walking into a screen that would refuse
 * them - and nothing more. Every route behind these links is enforced again by
 * {@code ProtectedRoute}, and every request those screens make is enforced a
 * third time by the API, which is the only one of the three that is
 * authoritative. A person who edits this file, or the URL bar, reaches
 * endpoints that check the same things and answer 401 or 403.</p>
 *
 * <p>Kept as data in a pure module so the whole role-to-navigation mapping can
 * be read in one place and tested without rendering anything.</p>
 */
export interface NavItem {
  /** The route, absolute so it can be compared with a location directly. */
  to: string;
  label: string;
  /** Which roles are offered this. The route guards on the same list. */
  roles: Role[];
  /** Matches child routes too, so a nested page keeps its parent highlighted. */
  end?: boolean;
}

export interface NavSection {
  /** Null for the first group, which needs no heading above it. */
  title: string | null;
  items: NavItem[];
}

const MEMBER: Role = 'ROLE_MEMBER';
const LIBRARIAN: Role = 'ROLE_LIBRARIAN';
const ADMIN: Role = 'ROLE_ADMIN';
const SUPER_ADMIN: Role = 'ROLE_SUPER_ADMIN';

/** Everybody who works at a library, plus the system role above them. */
const STAFF: Role[] = [LIBRARIAN, ADMIN, SUPER_ADMIN];

const EVERYONE: Role[] = [MEMBER, LIBRARIAN, ADMIN, SUPER_ADMIN];

/**
 * Every section of the application, with the roles offered each entry.
 *
 * <p>Ordered the way the work is: what you are looking at, then what you do
 * with it, then who does it, then the system underneath.</p>
 */
export const NAVIGATION: NavSection[] = [
  {
    title: null,
    items: [
      { to: '/app', label: 'Overview', roles: EVERYONE, end: true },
      { to: '/app/catalogue', label: 'Catalogue', roles: EVERYONE },
    ],
  },
  {
    title: 'My library',
    items: [
      { to: '/app/my-requests', label: 'My requests', roles: [MEMBER] },
      { to: '/app/loans', label: 'My loans', roles: [MEMBER] },
      { to: '/app/fines', label: 'Fines and payments', roles: [MEMBER] },
      { to: '/app/reading', label: 'Digital reading', roles: [MEMBER] },
    ],
  },
  {
    title: 'Circulation',
    items: [
      { to: '/app/circulation', label: 'Issue and return', roles: STAFF },
      { to: '/app/requests', label: 'Requests', roles: STAFF },
      { to: '/app/fines-owed', label: 'Fines owed', roles: STAFF },
    ],
  },
  {
    title: 'Manage',
    items: [
      { to: '/app/books', label: 'Books', roles: STAFF },
      { to: '/app/resources', label: 'Digital resources', roles: STAFF },
      { to: '/app/members', label: 'Members', roles: STAFF },
      { to: '/app/staff', label: 'Staff', roles: [ADMIN, SUPER_ADMIN] },
      { to: '/app/registrations', label: 'Registrations', roles: [ADMIN, SUPER_ADMIN] },
      { to: '/app/reports', label: 'Reports', roles: STAFF },
    ],
  },
  {
    title: 'System',
    items: [
      { to: '/app/libraries', label: 'Libraries', roles: [SUPER_ADMIN] },
      { to: '/app/audit', label: 'Audit log', roles: [ADMIN, SUPER_ADMIN] },
    ],
  },
  {
    title: 'Account',
    items: [{ to: '/app/profile', label: 'Profile', roles: EVERYONE }],
  },
];

/**
 * The sections this role is offered, with empty ones left out.
 *
 * <p>A section whose every entry belongs to another role is dropped rather than
 * rendered as a heading over nothing.</p>
 */
export function navigationFor(role: Role): NavSection[] {
  return NAVIGATION.map((section) => ({
    title: section.title,
    items: section.items.filter((item) => item.roles.includes(role)),
  })).filter((section) => section.items.length > 0);
}

/** Every route this role may open, which is what the route guards are built from. */
export function routesFor(role: Role): string[] {
  return navigationFor(role).flatMap((section) => section.items.map((item) => item.to));
}

/** Whether a role is offered a particular route. */
export function canOpen(role: Role, path: string): boolean {
  return routesFor(role).includes(path);
}

/**
 * The label for a path, for the heading and the document title.
 *
 * <p>Falls back to null rather than guessing: a page with no entry names itself.</p>
 */
export function labelFor(path: string): string | null {
  for (const section of NAVIGATION) {
    for (const item of section.items) {
      if (item.to === path) {
        return item.label;
      }
    }
  }

  return null;
}
