import { describe, expect, it } from 'vitest';
import { NAVIGATION, canOpen, labelFor, navigationFor, routesFor } from './navigation';
import { APP_SCREENS } from './AppRoutes';
import type { Role } from '@/types/api';

const ROLES: Role[] = ['ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_LIBRARIAN', 'ROLE_MEMBER'];

/**
 * What each role is offered.
 *
 * <p>Worth pinning because two lists have to agree: what the sidebar shows and
 * what the route guards allow. A link nobody may follow and a route nobody is
 * offered are both mistakes, and neither shows up as a type error.</p>
 */
describe('navigation by role', () => {
  it('offers a member their own shelf and nothing operational', () => {
    const routes = routesFor('ROLE_MEMBER');

    expect(routes).toContain('/app/catalogue');
    expect(routes).toContain('/app/loans');
    expect(routes).toContain('/app/fines');
    expect(routes).toContain('/app/reading');
    expect(routes).toContain('/app/profile');

    expect(routes).not.toContain('/app/books');
    expect(routes).not.toContain('/app/members');
    expect(routes).not.toContain('/app/circulation');
    expect(routes).not.toContain('/app/staff');
    expect(routes).not.toContain('/app/reports');
    expect(routes).not.toContain('/app/audit');
    expect(routes).not.toContain('/app/libraries');
  });

  it('offers a librarian the desk and the catalogue, not the administration', () => {
    const routes = routesFor('ROLE_LIBRARIAN');

    expect(routes).toContain('/app/circulation');
    expect(routes).toContain('/app/requests');
    expect(routes).toContain('/app/books');
    expect(routes).toContain('/app/members');

    // Reports are the desk's own figures, so a librarian gets them. What they
    // see is their library's, scoped by the server rather than by this list.
    expect(routes).toContain('/app/reports');

    expect(routes).not.toContain('/app/staff');
    expect(routes).not.toContain('/app/audit');
    expect(routes).not.toContain('/app/libraries');
    expect(routes).not.toContain('/app/loans');
  });

  it('offers an administrator their library, including staff and reports', () => {
    const routes = routesFor('ROLE_ADMIN');

    expect(routes).toContain('/app/staff');
    expect(routes).toContain('/app/registrations');
    expect(routes).toContain('/app/reports');
    expect(routes).toContain('/app/audit');
    expect(routes).toContain('/app/books');

    expect(routes).not.toContain('/app/libraries');
  });

  it('offers a super administrator the system, which nobody else sees', () => {
    expect(routesFor('ROLE_SUPER_ADMIN')).toContain('/app/libraries');

    for (const role of ROLES.filter((each) => each !== 'ROLE_SUPER_ADMIN')) {
      expect(routesFor(role)).not.toContain('/app/libraries');
    }
  });

  it('gives every role the overview, the catalogue and their profile', () => {
    for (const role of ROLES) {
      expect(routesFor(role)).toContain('/app');
      expect(routesFor(role)).toContain('/app/catalogue');
      expect(routesFor(role)).toContain('/app/profile');
    }
  });

  it('drops a section whose every entry belongs to another role', () => {
    const member = navigationFor('ROLE_MEMBER');

    expect(member.every((section) => section.items.length > 0), 'no heading over an empty list')
      .toBe(true);
    expect(member.map((section) => section.title)).not.toContain('Circulation');
    expect(member.map((section) => section.title)).not.toContain('System');
  });

  it('never offers a route twice to the same role', () => {
    for (const role of ROLES) {
      const routes = routesFor(role);
      expect(new Set(routes).size).toBe(routes.length);
    }
  });
});

describe('the navigation and the route guards agree', () => {
  it('every navigable route is a routed screen', () => {
    const screens = new Set(APP_SCREENS.map((screen) => screen.path));

    for (const section of NAVIGATION) {
      for (const item of section.items) {
        expect(screens, `${item.to} is in the sidebar`).toContain(item.to);
      }
    }
  });

  it('every routed screen is reachable by at least one role', () => {
    for (const screen of APP_SCREENS) {
      const reachable = ROLES.some((role) => canOpen(role, screen.path));
      expect(reachable, `${screen.path} is offered to somebody`).toBe(true);
    }
  });

  it('the roles offered a link are exactly the roles allowed the route', () => {
    // The real risk: a link somebody can see but not follow, or a route
    // nobody is shown. Both are silent, and neither is a type error.
    for (const screen of APP_SCREENS) {
      for (const role of ROLES) {
        expect(
          canOpen(role, screen.path),
          `${role} and ${screen.path}: sidebar and guard must agree`,
        ).toBe(screen.allow.includes(role));
      }
    }
  });
});

describe('labels', () => {
  it('names a known path and admits when it does not know one', () => {
    expect(labelFor('/app/books')).toBe('Books');
    expect(labelFor('/app/libraries')).toBe('Libraries');
    expect(labelFor('/app/nothing-here')).toBeNull();
  });
});
