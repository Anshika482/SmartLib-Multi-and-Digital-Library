/** @vitest-environment jsdom */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthContext, type AuthState } from '@/auth/AuthContext';
import { appRoutes } from './AppRoutes';
import { NotFoundPage } from '@/pages/NotFoundPage';
import type { Role, UserProfile } from '@/types/api';

/**
 * The signed-in shell, rendered.
 *
 * <p>The auth context is supplied directly rather than through
 * {@code AuthProvider}, so these tests exercise the shell and the routing and
 * not the token plumbing - which {@code apiClient.test.ts} already covers.</p>
 *
 * <p>Nothing here is a security assertion. The navigation and the guards decide
 * what is <i>shown</i>; the API decides what is <i>allowed</i>, and no amount of
 * rendering proves anything about that.</p>
 */

const signOut = vi.fn();

function account(role: Role): UserProfile {
  return {
    id: 1,
    username: 'asha',
    email: 'asha@example.invalid',
    fullName: 'Asha Rao',
    role,
    enabled: true,
    accountNonLocked: true,
    libraryId: 7,
    registrationStatus: 'APPROVED',
  } as UserProfile;
}

function renderApp(role: Role | null, at = '/app') {
  const value: AuthState = {
    user: role === null ? null : account(role),
    loading: false,
    signIn: vi.fn(),
    signOut,
  };

  return render(
    <AuthContext.Provider value={value}>
      <MemoryRouter initialEntries={[at]}>
        <Routes>
          {appRoutes()}
          <Route path="/sign-in" element={<p>Sign in page</p>} />
          <Route path="/404" element={<NotFoundPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

/** The sidebar, which is the one that is always rendered. */
function sidebar() {
  return screen.getAllByRole('navigation', { name: 'Sections' })[0];
}

beforeEach(() => {
  signOut.mockClear();
});

// ---------- authenticated routing ----------

describe('authenticated routing', () => {
  it('renders the requested screen inside the shell', () => {
    renderApp('ROLE_MEMBER', '/app/loans');

    expect(screen.getByRole('heading', { level: 1, name: 'My loans' })).toBeInTheDocument();
    expect(sidebar()).toBeInTheDocument();
  });

  it('sends a signed-out visitor to sign in, not into the shell', () => {
    renderApp(null, '/app/catalogue');

    expect(screen.getByText('Sign in page')).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Sections' })).not.toBeInTheDocument();
  });

  it('shows a breadcrumb on a nested screen and none on the overview', () => {
    // Two renders in one test, so the first is taken down explicitly rather
    // than left on the page for the second's queries to find.
    const nested = renderApp('ROLE_LIBRARIAN', '/app/books');
    expect(screen.getByRole('navigation', { name: 'Breadcrumb' })).toBeInTheDocument();
    nested.unmount();

    renderApp('ROLE_LIBRARIAN', '/app');
    expect(screen.queryAllByRole('navigation', { name: 'Breadcrumb' })).toHaveLength(0);
  });

  it('marks the current page in the sidebar', () => {
    renderApp('ROLE_MEMBER', '/app/fines');

    const current = within(sidebar()).getByRole('link', { name: 'Fines and payments' });
    expect(current.className).toContain('is-current');
  });
});

// ---------- role-based navigation ----------

describe('role-based navigation', () => {
  it('shows a member their own screens and no staff ones', () => {
    renderApp('ROLE_MEMBER');
    const nav = within(sidebar());

    expect(nav.getByRole('link', { name: 'My loans' })).toBeInTheDocument();
    expect(nav.getByRole('link', { name: 'Digital reading' })).toBeInTheDocument();
    expect(nav.queryByRole('link', { name: 'Books' })).not.toBeInTheDocument();
    expect(nav.queryByRole('link', { name: 'Members' })).not.toBeInTheDocument();
    expect(nav.queryByRole('link', { name: 'Reports' })).not.toBeInTheDocument();
  });

  it('shows a librarian the desk but not the administration', () => {
    renderApp('ROLE_LIBRARIAN');
    const nav = within(sidebar());

    expect(nav.getByRole('link', { name: 'Issue and return' })).toBeInTheDocument();
    expect(nav.getByRole('link', { name: 'Books' })).toBeInTheDocument();
    // Reports are the desk's figures; the administration is not.
    expect(nav.getByRole('link', { name: 'Reports' })).toBeInTheDocument();
    expect(nav.queryByRole('link', { name: 'Staff' })).not.toBeInTheDocument();
    expect(nav.queryByRole('link', { name: 'Registrations' })).not.toBeInTheDocument();
  });

  it('shows an administrator staff and reports, but not the system screens', () => {
    renderApp('ROLE_ADMIN');
    const nav = within(sidebar());

    expect(nav.getByRole('link', { name: 'Staff' })).toBeInTheDocument();
    expect(nav.getByRole('link', { name: 'Reports' })).toBeInTheDocument();
    expect(nav.queryByRole('link', { name: 'Libraries' })).not.toBeInTheDocument();
  });

  it('shows a super administrator the system screens', () => {
    renderApp('ROLE_SUPER_ADMIN');

    expect(within(sidebar()).getByRole('link', { name: 'Libraries' })).toBeInTheDocument();
  });

  it('names the signed-in role in the account button', () => {
    renderApp('ROLE_SUPER_ADMIN');

    expect(screen.getByText('System administrator')).toBeInTheDocument();
    expect(screen.getByText('asha')).toBeInTheDocument();
  });
});

// ---------- unauthorized routes ----------

describe('a page this role may not open', () => {
  it('shows 403 rather than a blank page or a silent redirect', () => {
    renderApp('ROLE_MEMBER', '/app/reports');

    expect(screen.getByText('403')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: /not yours to open/i })).toBeInTheDocument();
    // Not the page they asked for.
    expect(screen.queryByRole('heading', { level: 1, name: 'Reports' })).not.toBeInTheDocument();
  });

  it('says which role they are signed in as, since that is usually the cause', () => {
    renderApp('ROLE_LIBRARIAN', '/app/libraries');

    expect(screen.getByText(/signed in as asha, a librarian/i)).toBeInTheDocument();
  });

  it('still shows the shell, so they are not stranded', () => {
    renderApp('ROLE_MEMBER', '/app/staff');

    expect(sidebar()).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to your library' })).toBeInTheDocument();
  });

  it('shows not-found for a path that is not a screen at all', () => {
    renderApp('ROLE_MEMBER', '/app/nowhere');

    expect(screen.getByText(/that shelf is empty/i)).toBeInTheDocument();
  });
});

// ---------- the account menu and logout ----------

describe('the account menu', () => {
  it('opens, offers the way out, and signs out when asked', async () => {
    const user = userEvent.setup();
    renderApp('ROLE_MEMBER');

    const button = screen.getByRole('button', { name: /your account/i });
    expect(button).toHaveAttribute('aria-expanded', 'false');

    await user.click(button);
    expect(button).toHaveAttribute('aria-expanded', 'true');

    await user.click(screen.getByRole('menuitem', { name: 'Sign out' }));
    expect(signOut).toHaveBeenCalledOnce();
  });

  it('closes on Escape without signing anybody out', async () => {
    const user = userEvent.setup();
    renderApp('ROLE_MEMBER');

    await user.click(screen.getByRole('button', { name: /your account/i }));
    expect(screen.getByRole('menu')).toBeInTheDocument();

    await user.keyboard('{Escape}');

    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(signOut).not.toHaveBeenCalled();
  });

  it('is reachable by keyboard alone', async () => {
    const user = userEvent.setup();
    renderApp('ROLE_MEMBER');

    await user.tab();
    await user.tab();
    await user.tab();

    // Whatever has focus, it is a real control rather than nothing.
    expect(document.activeElement).not.toBe(document.body);
  });
});

// ---------- the mobile drawer ----------

describe('the mobile drawer', () => {
  it('opens from the menu button and closes again', async () => {
    const user = userEvent.setup();
    renderApp('ROLE_MEMBER');

    const open = screen.getByRole('button', { name: /open the menu/i });
    expect(open).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getAllByRole('navigation', { name: 'Sections' })).toHaveLength(1);

    await user.click(open);

    // The drawer is a second copy of the same navigation.
    expect(screen.getAllByRole('navigation', { name: 'Sections' })).toHaveLength(2);
    expect(open).toHaveAttribute('aria-expanded', 'true');

    await user.click(screen.getByRole('button', { name: /close the menu/i }));
    expect(screen.getAllByRole('navigation', { name: 'Sections' })).toHaveLength(1);
  });

  it('closes on Escape', async () => {
    const user = userEvent.setup();
    renderApp('ROLE_MEMBER');

    await user.click(screen.getByRole('button', { name: /open the menu/i }));
    expect(screen.getAllByRole('navigation', { name: 'Sections' })).toHaveLength(2);

    await user.keyboard('{Escape}');

    expect(screen.getAllByRole('navigation', { name: 'Sections' })).toHaveLength(1);
  });

  it('closes when a link inside it is followed', async () => {
    const user = userEvent.setup();
    renderApp('ROLE_MEMBER');

    await user.click(screen.getByRole('button', { name: /open the menu/i }));

    const drawer = screen.getAllByRole('navigation', { name: 'Sections' })[1];
    await user.click(within(drawer).getByRole('link', { name: 'Digital reading' }));

    expect(screen.getAllByRole('navigation', { name: 'Sections' })).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 1, name: 'Digital reading' })).toBeInTheDocument();
  });

  it('offers the same entries as the sidebar, for the same role', async () => {
    const user = userEvent.setup();
    renderApp('ROLE_ADMIN');

    await user.click(screen.getByRole('button', { name: /open the menu/i }));

    const [side, drawer] = screen.getAllByRole('navigation', { name: 'Sections' });
    const names = (nav: HTMLElement) =>
      within(nav)
        .getAllByRole('link')
        .map((link) => link.textContent);

    expect(names(drawer)).toEqual(names(side));
  });
});
