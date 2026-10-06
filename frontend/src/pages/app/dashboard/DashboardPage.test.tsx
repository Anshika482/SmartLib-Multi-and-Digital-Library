/** @vitest-environment jsdom */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { AuthContext, type AuthState } from '@/auth/AuthContext';
import { DashboardPage } from './DashboardPage';
import { dashboardService } from '@/services/dashboardService';
import { ApiError } from '@/services/apiClient';
import type { Dashboard, Role, UserProfile } from '@/types/api';

/**
 * The overview screen.
 *
 * <p>The service is stubbed, so these tests are about what the page does with
 * what the server sends - not about the figures themselves, which
 * {@code DashboardServiceTest} covers against the real queries.</p>
 *
 * <p>The important property: the page draws a section only when the response
 * carries it. A member's response has no circulation figures in it at all, so
 * there is no role check here that could be got wrong.</p>
 */

const load = vi.spyOn(dashboardService, 'load');

function account(role: Role): UserProfile {
  return {
    id: 1,
    username: 'asha',
    email: 'asha@example.invalid',
    role,
    enabled: true,
    accountNonLocked: true,
  } as UserProfile;
}

/** A response with only the sections the server would actually send that role. */
function dashboard(role: Role, overrides: Partial<Dashboard> = {}): Dashboard {
  const staff = role !== 'ROLE_MEMBER';

  return {
    role,
    libraryName: 'Central Library',
    catalogue: { titles: 25, categories: 13, digitalResources: 7 },
    member:
      role === 'ROLE_MEMBER'
        ? { currentLoans: 2, overdueLoans: 1, unpaidFines: 1, amountOwed: 3.5 }
        : null,
    circulation: staff
      ? { activeLoans: 4, overdueLoans: 2, unpaidFines: 3, finesOutstanding: 12.5 }
      : null,
    people:
      role === 'ROLE_ADMIN' || role === 'ROLE_SUPER_ADMIN'
        ? { members: 5, librarians: 2, administrators: 1, pendingRegistrations: 2 }
        : null,
    system:
      role === 'ROLE_SUPER_ADMIN'
        ? { libraries: 3, accounts: 40, pendingLibraryApplications: 1 }
        : null,
    recentActivity: staff ? [{ action: 'BOOK_ISSUED', occurredAt: '2026-09-24T10:00:00' }] : [],
    ...overrides,
  };
}

function renderDashboard(role: Role) {
  const value: AuthState = {
    user: account(role),
    loading: false,
    signIn: vi.fn(),
    signOut: vi.fn(),
  };

  return render(
    <AuthContext.Provider value={value}>
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

function section(name: string) {
  return screen.getByRole('heading', { level: 2, name }).parentElement as HTMLElement;
}

beforeEach(() => {
  load.mockReset();
});

// ---------- what each role is shown ----------

describe('a member', () => {
  it('sees their own borrowing and the catalogue, and nothing operational', async () => {
    load.mockResolvedValue(dashboard('ROLE_MEMBER'));
    renderDashboard('ROLE_MEMBER');

    await screen.findByRole('heading', { level: 2, name: 'Your borrowing' });

    expect(within(section('Your borrowing')).getByText('2')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Catalogue' })).toBeInTheDocument();

    expect(screen.queryByRole('heading', { level: 2, name: 'Circulation' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 2, name: 'People' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 2, name: 'System' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 2, name: 'Recent activity' })).not.toBeInTheDocument();
  });

  it('shows what is owed to two decimal places', async () => {
    load.mockResolvedValue(dashboard('ROLE_MEMBER'));
    renderDashboard('ROLE_MEMBER');

    expect(await screen.findByText('3.50')).toBeInTheDocument();
  });

  it('links each figure to the screen that explains it', async () => {
    load.mockResolvedValue(dashboard('ROLE_MEMBER'));
    renderDashboard('ROLE_MEMBER');

    await screen.findByRole('heading', { level: 2, name: 'Your borrowing' });

    expect(screen.getByRole('link', { name: /Current loans/ })).toHaveAttribute('href', '/app/loans');
    expect(screen.getByRole('link', { name: /Unpaid fines/ })).toHaveAttribute('href', '/app/fines');
  });
});

describe('a librarian', () => {
  it('sees circulation and activity, but not the people or the system', async () => {
    load.mockResolvedValue(dashboard('ROLE_LIBRARIAN'));
    renderDashboard('ROLE_LIBRARIAN');

    await screen.findByRole('heading', { level: 2, name: 'Circulation' });

    expect(screen.getByRole('heading', { level: 2, name: 'Recent activity' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 2, name: 'Your borrowing' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 2, name: 'People' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 2, name: 'System' })).not.toBeInTheDocument();
  });
});

describe('an administrator', () => {
  it('sees the people as well, with what is waiting flagged', async () => {
    load.mockResolvedValue(dashboard('ROLE_ADMIN'));
    renderDashboard('ROLE_ADMIN');

    await screen.findByRole('heading', { level: 2, name: 'People' });

    expect(within(section('People')).getByText('Needs a decision')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 2, name: 'System' })).not.toBeInTheDocument();
  });
});

describe('a super administrator', () => {
  it('sees the system counts', async () => {
    load.mockResolvedValue(dashboard('ROLE_SUPER_ADMIN'));
    renderDashboard('ROLE_SUPER_ADMIN');

    await screen.findByRole('heading', { level: 2, name: 'System' });

    const system = within(section('System'));
    expect(system.getByText('3')).toBeInTheDocument();
    expect(system.getByText('40')).toBeInTheDocument();
  });
});

// ---------- the states ----------

describe('dashboard states', () => {
  it('shows a loading placeholder before anything arrives', () => {
    load.mockReturnValue(new Promise(() => {}));
    const { container } = renderDashboard('ROLE_MEMBER');

    expect(container.querySelectorAll('.sl-skeleton').length).toBeGreaterThan(0);
    expect(screen.queryByRole('heading', { level: 2, name: 'Catalogue' })).not.toBeInTheDocument();
  });

  it("shows the API's own message and a way to try again when it fails", async () => {
    load.mockRejectedValue(new ApiError(500, 'The library service is having a moment.'));
    renderDashboard('ROLE_MEMBER');

    expect(await screen.findByText('The library service is having a moment.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('retries when asked, and renders once it works', async () => {
    const user = userEvent.setup();
    load.mockRejectedValueOnce(new ApiError(500, 'Not this time.'));
    renderDashboard('ROLE_MEMBER');

    await screen.findByText('Not this time.');

    load.mockResolvedValue(dashboard('ROLE_MEMBER'));
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 2, name: 'Your borrowing' })).toBeInTheDocument(),
    );
  });

  it('shows zeros rather than inventing anything for a library with nothing in it', async () => {
    load.mockResolvedValue(
      dashboard('ROLE_LIBRARIAN', {
        catalogue: { titles: 0, categories: 0, digitalResources: 0 },
        circulation: { activeLoans: 0, overdueLoans: 0, unpaidFines: 0, finesOutstanding: 0 },
        recentActivity: [],
      }),
    );
    renderDashboard('ROLE_LIBRARIAN');

    await screen.findByRole('heading', { level: 2, name: 'Circulation' });

    expect(within(section('Circulation')).getAllByText('0').length).toBeGreaterThan(0);
    expect(within(section('Circulation')).getByText('0.00')).toBeInTheDocument();
    // An empty trail is not rendered as an empty box.
    expect(screen.queryByRole('heading', { level: 2, name: 'Recent activity' })).not.toBeInTheDocument();
  });

  it('asks the server once, with no role in the request', async () => {
    load.mockResolvedValue(dashboard('ROLE_MEMBER'));
    renderDashboard('ROLE_MEMBER');

    await screen.findByRole('heading', { level: 2, name: 'Your borrowing' });

    expect(load).toHaveBeenCalledTimes(1);
    // The signal is the only argument: there is no role to pass and nothing a
    // client could widen.
    expect(load.mock.calls[0].length).toBeLessThanOrEqual(1);
  });
});

describe('recent activity', () => {
  it('reads an audit action as a sentence, and names nobody', async () => {
    load.mockResolvedValue(dashboard('ROLE_ADMIN'));
    renderDashboard('ROLE_ADMIN');

    await screen.findByRole('heading', { level: 2, name: 'Recent activity' });

    const activity = within(section('Recent activity'));
    expect(activity.getByText('Book issued')).toBeInTheDocument();

    // Scoped to the list: the page greets the signed-in person by name in its
    // own heading, which is their name and nobody else's. What must not appear
    // is an actor or a target beside an audit line.
    expect(activity.queryByText(/asha/)).not.toBeInTheDocument();
  });
});
