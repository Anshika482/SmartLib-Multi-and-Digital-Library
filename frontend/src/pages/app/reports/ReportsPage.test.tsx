/** @vitest-environment jsdom */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { AuthContext, type AuthState } from '@/auth/AuthContext';
import { ReportsPage } from './ReportsPage';
import { csvField, reportToCsv } from './reportCsv';
import { reportService } from '@/services/reportService';
import { ApiError } from '@/services/apiClient';
import type { Report, Role, UserProfile } from '@/types/api';

/**
 * Reports.
 *
 * <p>The service is stubbed, so these are about the query this page sends and
 * what it draws. Whether the figures are right is settled against a real
 * database by {@code ReportIntegrationTest}, which checks them against numbers
 * worked out on paper.</p>
 *
 * <p>The property pinned hardest: <b>this page adds nothing up</b>. Every total
 * on screen is one the server sent. A test below feeds totals that do not agree
 * with the rows beneath them and requires the server's totals to be what is
 * shown, because a page that recomputed would disagree with the accounts the
 * moment a list was paged.</p>
 */

const load = vi.spyOn(reportService, 'load');

function report(overrides: Partial<Report> = {}): Report {
  return {
    scope: 'ROLE_LIBRARIAN',
    systemWide: false,
    libraryName: 'Central Library',
    from: '2026-01-01',
    to: '2026-03-31',
    totals: {
      totalBooks: 25,
      totalMembers: 8,
      issues: 40,
      returns: 33,
      activeLoans: 7,
      overdueLoans: 2,
      finesRaised: 12.5,
      finesPaid: 4.25,
      finesOutstanding: 8.25,
      paymentsTaken: 4.25,
    },
    mostIssued: [
      { title: 'Dune', author: 'Frank Herbert', issues: 9 },
      { title: 'Ubik', author: 'Philip K. Dick', issues: 5 },
    ],
    categories: [{ category: 'Science fiction', issues: 14 }],
    circulation: [
      { year: 2026, month: 1, issues: 20, returns: 18 },
      { year: 2026, month: 2, issues: 12, returns: 15 },
      { year: 2026, month: 3, issues: 8, returns: 0 },
    ],
    overdueTrend: [{ year: 2026, month: 2, count: 3 }],
    ...overrides,
  };
}

function renderReports(role: Role = 'ROLE_LIBRARIAN') {
  const value: AuthState = {
    user: { id: 1, username: 'asha', email: 'a@b.invalid', role, enabled: true } as UserProfile,
    loading: false,
    signIn: vi.fn(),
    signOut: vi.fn(),
  };

  return render(
    <AuthContext.Provider value={value}>
      <MemoryRouter>
        <ReportsPage />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

function section(name: string): HTMLElement {
  return screen.getByRole('heading', { level: 2, name }).parentElement as HTMLElement;
}

beforeEach(() => {
  load.mockReset();
  load.mockResolvedValue(report());
});

// ---------- loading it ----------

describe('loading a report', () => {
  it('asks for a range and nothing else - no library to send', async () => {
    renderReports();

    await waitFor(() => expect(load).toHaveBeenCalled());

    const [from, to] = load.mock.calls[0];
    expect(from).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(to).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    // The scope is the server's decision, from the token.
    expect(load.mock.calls[0]).toHaveLength(3);
  });

  it('opens on a range ending today', async () => {
    renderReports();

    await waitFor(() => expect(load).toHaveBeenCalled());

    const today = new Date().toISOString().slice(0, 10);
    expect(load.mock.calls[0][1]).toBe(today);
  });

  it('names the library it is describing', async () => {
    renderReports();

    expect(await screen.findByText('Central Library')).toBeInTheDocument();
  });

  it('says when it spans every library instead of naming one', async () => {
    load.mockResolvedValue(
      report({ scope: 'ROLE_SUPER_ADMIN', systemWide: true, libraryName: null }),
    );
    renderReports('ROLE_SUPER_ADMIN');

    expect(await screen.findByText('Every library on this deployment.')).toBeInTheDocument();
  });
});

// ---------- the filters ----------

describe('the date filters', () => {
  it('reloads with the range that was chosen', async () => {
    const user = userEvent.setup();
    renderReports();
    await screen.findByText('Central Library');

    await user.clear(screen.getByLabelText('From'));
    await user.type(screen.getByLabelText('From'), '2025-06-01');
    await user.clear(screen.getByLabelText('To'));
    await user.type(screen.getByLabelText('To'), '2025-12-31');
    await user.click(screen.getByRole('button', { name: 'Show' }));

    // The third argument is the AbortSignal useAsync supplies, so only the two
    // dates are asserted here.
    await waitFor(() => {
      const last = load.mock.calls[load.mock.calls.length - 1];
      expect(last[0]).toBe('2025-06-01');
      expect(last[1]).toBe('2025-12-31');
    });
  });

  it('will not send an inverted range, and says why', async () => {
    const user = userEvent.setup();
    renderReports();
    await screen.findByText('Central Library');

    const calls = load.mock.calls.length;

    await user.clear(screen.getByLabelText('To'));
    await user.type(screen.getByLabelText('To'), '2000-01-01');

    expect(await screen.findByRole('alert')).toHaveTextContent('end date is before the start date');
    expect(screen.getByRole('button', { name: 'Show' })).toBeDisabled();
    expect(load).toHaveBeenCalledTimes(calls);
  });
});

// ---------- the figures ----------

describe('the figures', () => {
  it('shows what happened in the period', async () => {
    renderReports();

    await screen.findByText('Central Library');

    const period = within(section('In this period'));
    expect(period.getByText('40')).toBeInTheDocument();
    expect(period.getByText('33')).toBeInTheDocument();
    expect(period.getByText('12.50')).toBeInTheDocument();
  });

  it('shows what is true now, separately from the period', async () => {
    renderReports();

    await screen.findByText('Central Library');

    const now = within(section('As things stand'));
    expect(now.getByText('25')).toBeInTheDocument();
    expect(now.getByText('7')).toBeInTheDocument();
    expect(now.getByText('8.25')).toBeInTheDocument();
  });

  it('renders the server’s totals even when they disagree with the rows', async () => {
    // Deliberately inconsistent: the two listed books add to 14, not 40. The
    // server's 40 must win, because the list is only the top few.
    load.mockResolvedValue(report());
    renderReports();

    await screen.findByText('Central Library');

    expect(within(section('In this period')).getByText('40')).toBeInTheDocument();
  });

  it('lists the books borrowed most, in the order given', async () => {
    const { container } = renderReports();

    await screen.findByText('Dune');

    const titles = [...container.querySelectorAll('tbody tr td:first-child')].map(
      (cell) => cell.textContent,
    );
    expect(titles.slice(0, 2)).toEqual(['Dune', 'Ubik']);
  });

  it('breaks circulation down by month, with the overdue count beside it', async () => {
    renderReports();

    await screen.findByRole('heading', { level: 2, name: 'Month by month' });

    const monthly = within(section('Month by month'));
    expect(monthly.getByText('Jan 2026')).toBeInTheDocument();
    expect(monthly.getByText('Mar 2026')).toBeInTheDocument();
    // February had three fall overdue; the other months had none.
    expect(monthly.getByText('3')).toBeInTheDocument();
  });

  it('shows each bar’s number as text, not only as a length', async () => {
    renderReports();

    await screen.findByRole('heading', { level: 2, name: 'Month by month' });

    // A bar nobody can read is a decoration, and a screen reader gets nothing.
    expect(within(section('Month by month')).getByText('20')).toBeInTheDocument();
  });
});

// ---------- empty and error ----------

describe('report states', () => {
  it('shows a placeholder while it loads', () => {
    load.mockReturnValue(new Promise(() => {}));
    const { container } = renderReports();

    expect(container.querySelectorAll('.sl-skeleton').length).toBeGreaterThan(0);
  });

  it('says a quiet period was quiet rather than showing empty tables', async () => {
    load.mockResolvedValue(
      report({
        totals: { ...report().totals, issues: 0, returns: 0 },
        mostIssued: [],
        categories: [],
        circulation: [],
        overdueTrend: [],
      }),
    );
    renderReports();

    await screen.findByRole('heading', { level: 2, name: 'Borrowed most' });

    expect(screen.getAllByText('Nothing went out in this period.').length).toBeGreaterThan(0);
    expect(screen.getByText('No borrowing in this period.')).toBeInTheDocument();
  });

  it("passes on the server's own message when a range is refused", async () => {
    load.mockRejectedValue(new ApiError(400, 'A report can cover at most 1830 days.'));
    renderReports();

    expect(await screen.findByText('A report can cover at most 1830 days.')).toBeInTheDocument();
  });

  it('retries when asked', async () => {
    const user = userEvent.setup();
    load.mockRejectedValueOnce(new ApiError(500, 'Not this time.'));
    renderReports();
    await screen.findByText('Not this time.');

    load.mockResolvedValue(report());
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    await waitFor(() => expect(screen.getByText('Central Library')).toBeInTheDocument());
  });

  it('offers no export until there is something to export', () => {
    load.mockReturnValue(new Promise(() => {}));
    renderReports();

    expect(screen.queryByRole('button', { name: 'Export CSV' })).not.toBeInTheDocument();
  });
});

// ---------- the export ----------

describe('the CSV export', () => {
  it('carries the same figures that are on screen', () => {
    const csv = reportToCsv(report());

    expect(csv).toContain('Issues,40');
    expect(csv).toContain('Returns,33');
    expect(csv).toContain('Fines raised,12.50');
    expect(csv).toContain('Dune,Frank Herbert,9');
    expect(csv).toContain('Science fiction,14');
    expect(csv).toContain('Jan 2026,20,18,0');
    // February's overdue count lands on February's row.
    expect(csv).toContain('Feb 2026,12,15,3');
  });

  it('says which library and which dates it covers', () => {
    const csv = reportToCsv(report());

    expect(csv).toContain('Scope,Central Library');
    expect(csv).toContain('From,2026-01-01');
    expect(csv).toContain('To,2026-03-31');
  });

  it('says so when it covers every library', () => {
    expect(reportToCsv(report({ systemWide: true, libraryName: null }))).toContain(
      'Scope,All libraries',
    );
  });

  it('quotes a title containing a comma rather than splitting it into two columns', () => {
    const csv = reportToCsv(
      report({ mostIssued: [{ title: 'Emma, or the Parish', author: 'Austen', issues: 3 }] }),
    );

    expect(csv).toContain('"Emma, or the Parish",Austen,3');
  });

  it('doubles an embedded quote, as the CSV rule requires', () => {
    expect(csvField('He said "no"')).toBe('"He said ""no"""');
  });

  it('defuses a title a spreadsheet would run as a formula', () => {
    // A title starting with = would be executed on opening the file.
    expect(csvField('=SUM(A1:A9)')).toBe("'=SUM(A1:A9)");
    expect(csvField('+1')).toBe("'+1");
    expect(csvField('-2')).toBe("'-2");
    expect(csvField('@here')).toBe("'@here");
  });

  it('leaves an ordinary field alone', () => {
    expect(csvField('Dune')).toBe('Dune');
    expect(csvField(42)).toBe('42');
  });
});
