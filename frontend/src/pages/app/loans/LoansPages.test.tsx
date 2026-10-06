/** @vitest-environment jsdom */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { AuthContext, type AuthState } from '@/auth/AuthContext';
import { MyLoansPage } from './MyLoansPage';
import { FinesPage } from './FinesPage';
import { loanService } from '@/services/loanService';
import { ApiError } from '@/services/apiClient';
import type { Page, Role, Transaction, UserProfile } from '@/types/api';

/**
 * The loans and fines screens.
 *
 * <p>The service is stubbed, so these are about what each screen asks for and
 * what it draws. Whether a fine is right is settled by {@code OverduePolicy} and
 * proven against a real database by {@code OverdueFineIntegrationTest} and
 * {@code OutstandingFinesIntegrationTest}.</p>
 *
 * <p>The property pinned hardest here: <b>no screen computes anything</b>. Days
 * late and amounts owed are rendered exactly as the server sent them. A test
 * below feeds a deliberately inconsistent row - dates that say one thing,
 * {@code daysOverdue} another - and requires the server's number to win, because
 * a page that recomputed would be a second copy of the rule.</p>
 */

const forUser = vi.spyOn(loanService, 'forUser');
const fines = vi.spyOn(loanService, 'fines');
const byStatus = vi.spyOn(loanService, 'byStatus');

function loan(id: number, overrides: Partial<Transaction> = {}): Transaction {
  return {
    id,
    bookId: 100 + id,
    bookTitle: `Book ${id}`,
    bookAuthor: `Author ${id}`,
    userId: 1,
    issueDate: '2026-09-01',
    dueDate: '2026-09-15',
    returnDate: null,
    fineAmount: null,
    daysOverdue: 0,
    status: 'ISSUED',
    finePaymentStatus: 'NOT_REQUIRED',
    finePaidAt: null,
    ...overrides,
  };
}

function page(content: Transaction[]): Page<Transaction> {
  return { content, page: 0, size: 50, totalElements: content.length, totalPages: 1 };
}

function renderPage(node: React.ReactElement, role: Role) {
  const value: AuthState = {
    user: { id: 1, username: 'asha', email: 'a@b.invalid', role, enabled: true } as UserProfile,
    loading: false,
    signIn: vi.fn(),
    signOut: vi.fn(),
  };

  return render(
    <AuthContext.Provider value={value}>
      <MemoryRouter>{node}</MemoryRouter>
    </AuthContext.Provider>,
  );
}

const renderLoans = () => renderPage(<MyLoansPage />, 'ROLE_MEMBER');
const renderFines = (role: Role = 'ROLE_MEMBER') => renderPage(<FinesPage />, role);

function row(title: string): HTMLElement {
  return screen.getByText(title).closest('li') as HTMLElement;
}

beforeEach(() => {
  [forUser, fines, byStatus].forEach((spy) => spy.mockReset());
  forUser.mockResolvedValue(page([loan(1)]));
  fines.mockResolvedValue(page([]));
  byStatus.mockResolvedValue(page([]));
});

// ========== a member's loans ==========

describe('my loans', () => {
  it('asks for the signed-in account and nobody else', async () => {
    renderLoans();

    await screen.findByText('Book 1');

    expect(forUser.mock.calls[0][0]).toBe(1);
  });

  it('names the book without a second request per row', async () => {
    renderLoans();

    await screen.findByText('Book 1');

    // bookTitle comes on the loan itself, so nothing here fetches a book.
    expect(screen.getByText('Author 1')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Book 1' })).toHaveAttribute('href', '/app/catalogue/101');
  });

  it('shows the due date on an open loan', async () => {
    renderLoans();

    await screen.findByText('Book 1');

    expect(within(row('Book 1')).getByText('2026-09-15')).toBeInTheDocument();
    expect(within(row('Book 1')).getByText('On loan')).toBeInTheDocument();
  });

  it('says how many books are out, counting only the open ones', async () => {
    forUser.mockResolvedValue(
      page([
        loan(1),
        loan(2, { status: 'RETURNED', returnDate: '2026-09-10' }),
        loan(3, { status: 'OVERDUE', daysOverdue: 2, fineAmount: 0.7 }),
      ]),
    );
    renderLoans();

    expect(await screen.findByText('2 books out.')).toBeInTheDocument();
  });

  it('puts what is still out above what has come back', async () => {
    forUser.mockResolvedValue(
      page([
        loan(1, { status: 'RETURNED', returnDate: '2026-09-10', dueDate: '2026-09-09' }),
        loan(2, { status: 'ISSUED', dueDate: '2026-09-30' }),
      ]),
    );
    const { container } = renderLoans();

    await screen.findByText('Book 2');

    const titles = [...container.querySelectorAll('.sl-loan__title')].map((node) => node.textContent);
    expect(titles).toEqual(['Book 2', 'Book 1']);
  });

  it('says nothing is out when nothing is', async () => {
    forUser.mockResolvedValue(page([loan(1, { status: 'RETURNED', returnDate: '2026-09-10' })]));
    renderLoans();

    expect(await screen.findByText('Nothing out at the moment.')).toBeInTheDocument();
  });
});

// ========== what is late ==========

describe('an overdue loan', () => {
  it('shows the days late the server counted, not one this page worked out', async () => {
    // Deliberately inconsistent: the dates suggest a different number from
    // daysOverdue. The server's figure must be the one shown, because the
    // browser's idea of today is not the server's.
    forUser.mockResolvedValue(
      page([
        loan(1, {
          status: 'OVERDUE',
          dueDate: '2020-01-01',
          daysOverdue: 3,
          fineAmount: 1.05,
        }),
      ]),
    );
    renderLoans();

    await screen.findByText('Book 1');

    expect(screen.getByText('3 days late')).toBeInTheDocument();
    expect(screen.queryByText(/2404 days late/)).not.toBeInTheDocument();
  });

  it('says one day late in the singular', async () => {
    forUser.mockResolvedValue(
      page([loan(1, { status: 'OVERDUE', daysOverdue: 1, fineAmount: 0.35 })]),
    );
    renderLoans();

    expect(await screen.findByText('1 day late')).toBeInTheDocument();
  });

  it('marks an open fine as still growing rather than payable', async () => {
    forUser.mockResolvedValue(
      page([loan(1, { status: 'OVERDUE', daysOverdue: 2, fineAmount: 0.7, finePaymentStatus: 'UNPAID' })]),
    );
    renderLoans();

    await screen.findByText('Book 1');

    expect(screen.getByText('0.70')).toBeInTheDocument();
    expect(screen.getByText('so far')).toBeInTheDocument();
  });

  it('shows no lateness and no fine on a loan due today', async () => {
    forUser.mockResolvedValue(page([loan(1, { status: 'ISSUED', daysOverdue: 0, fineAmount: null })]));
    renderLoans();

    await screen.findByText('Book 1');

    expect(screen.queryByText(/days late/)).not.toBeInTheDocument();
    expect(screen.queryByText('so far')).not.toBeInTheDocument();
  });
});

// ========== fines ==========

describe("a member's fines", () => {
  it('asks the fines endpoint with no id, because the server knows whose', async () => {
    renderFines();

    await waitFor(() => expect(fines).toHaveBeenCalled());

    expect(JSON.stringify(fines.mock.calls[0][0] ?? {})).not.toContain('user');
  });

  it('adds up what the server sent and says it is owed', async () => {
    fines.mockResolvedValue(
      page([
        loan(1, { status: 'RETURNED', returnDate: '2026-09-20', fineAmount: 1.05, daysOverdue: 3, finePaymentStatus: 'UNPAID' }),
        loan(2, { status: 'RETURNED', returnDate: '2026-09-20', fineAmount: 0.7, daysOverdue: 2, finePaymentStatus: 'UNPAID' }),
      ]),
    );
    renderFines();

    expect(await screen.findByText('1.75')).toBeInTheDocument();
    expect(screen.getByText('owed. Settle it at the desk.')).toBeInTheDocument();
  });

  it('marks a settled fine as payable rather than growing', async () => {
    fines.mockResolvedValue(
      page([loan(1, { status: 'RETURNED', returnDate: '2026-09-20', fineAmount: 1.05, daysOverdue: 3, finePaymentStatus: 'UNPAID' })]),
    );
    renderFines();

    await screen.findByText('Book 1');

    expect(screen.getByText('to pay')).toBeInTheDocument();
  });

  it('never asks a member for the overdue list, which is staff-only', async () => {
    renderFines('ROLE_MEMBER');

    await waitFor(() => expect(fines).toHaveBeenCalled());

    expect(byStatus).not.toHaveBeenCalled();
  });

  it('says a member owes nothing without calling it an error', async () => {
    fines.mockResolvedValue(page([]));
    renderFines();

    expect(await screen.findByText('You owe nothing')).toBeInTheDocument();
  });

  it('offers to pay a settled fine that is still owed', async () => {
    fines.mockResolvedValue(
      page([loan(1, { status: 'RETURNED', returnDate: '2026-09-20', fineAmount: 1.05, finePaymentStatus: 'UNPAID' })]),
    );
    renderFines();

    await screen.findByText('Book 1');

    expect(screen.getByRole('button', { name: 'Pay fine' })).toBeInTheDocument();
  });

  it('offers no payment on a fine that is already paid', async () => {
    fines.mockResolvedValue(
      page([loan(1, { status: 'RETURNED', returnDate: '2026-09-20', fineAmount: 1.05, finePaymentStatus: 'PAID' })]),
    );
    renderFines();

    await screen.findByText('Book 1');

    expect(screen.queryByRole('button', { name: 'Pay fine' })).not.toBeInTheDocument();
  });

  it('offers no payment while the book is still out', async () => {
    // The fine is still growing, and the server refuses to settle one that is
    // not final. Offering the button would only produce a refusal.
    forUser.mockResolvedValue(page([]));
    fines.mockResolvedValue(
      page([loan(1, { status: 'OVERDUE', daysOverdue: 3, fineAmount: 1.05, finePaymentStatus: 'UNPAID' })]),
    );
    renderFines();

    await screen.findByText('Book 1');

    expect(screen.queryByRole('button', { name: 'Pay fine' })).not.toBeInTheDocument();
  });

  it('offers staff no pay button: they settle at the desk through their own endpoint', async () => {
    fines.mockResolvedValue(
      page([loan(1, { status: 'RETURNED', returnDate: '2026-09-20', fineAmount: 1.05, finePaymentStatus: 'UNPAID' })]),
    );
    renderFines('ROLE_LIBRARIAN');

    await screen.findByText('Book 1');

    expect(screen.queryByRole('button', { name: 'Pay fine' })).not.toBeInTheDocument();
  });
});

// ========== the staff view ==========

describe('the staff fines view', () => {
  it('shows the library framing rather than a personal one', async () => {
    fines.mockResolvedValue(
      page([loan(1, { status: 'RETURNED', returnDate: '2026-09-20', fineAmount: 1.05, finePaymentStatus: 'UNPAID' })]),
    );
    renderFines('ROLE_LIBRARIAN');

    expect(await screen.findByRole('heading', { level: 1, name: 'Fines owed' })).toBeInTheDocument();
    expect(screen.getByText(/outstanding across 1 loan/)).toBeInTheDocument();
  });

  it('also asks for what is still out and running late', async () => {
    renderFines('ROLE_LIBRARIAN');

    await waitFor(() => expect(byStatus).toHaveBeenCalled());

    expect(byStatus.mock.calls[0][0]).toBe('OVERDUE');
  });

  it('lists the accruing loans separately from the payable ones', async () => {
    fines.mockResolvedValue(
      page([loan(1, { status: 'RETURNED', returnDate: '2026-09-20', fineAmount: 1.05, finePaymentStatus: 'UNPAID' })]),
    );
    byStatus.mockResolvedValue(page([loan(9, { status: 'OVERDUE', daysOverdue: 4, fineAmount: 1.4 })]));
    renderFines('ROLE_ADMIN');

    await screen.findByRole('heading', { level: 2, name: 'Still out and running late' });

    expect(screen.getByText('Book 9')).toBeInTheDocument();
    expect(screen.getByText('4 days late')).toBeInTheDocument();
  });

  it('names the borrower by account id only, never by name or email', async () => {
    fines.mockResolvedValue(
      page([
        loan(1, {
          status: 'RETURNED',
          returnDate: '2026-09-20',
          fineAmount: 1.05,
          finePaymentStatus: 'UNPAID',
          userId: 42,
        }),
      ]),
    );
    const { container } = renderFines('ROLE_LIBRARIAN');

    await screen.findByText('Book 1');

    expect(screen.getByText('#42')).toBeInTheDocument();
    expect(container.textContent).not.toContain('@');
  });

  it('tells staff nothing is outstanding in their own terms', async () => {
    fines.mockResolvedValue(page([]));
    renderFines('ROLE_LIBRARIAN');

    expect(await screen.findByText('Nothing outstanding')).toBeInTheDocument();
  });
});

// ========== states ==========

describe('loan and fine screen states', () => {
  it('shows a placeholder before loans arrive', () => {
    forUser.mockReturnValue(new Promise(() => {}));
    const { container } = renderLoans();

    expect(container.querySelectorAll('.sl-skeleton').length).toBeGreaterThan(0);
  });

  it("shows the API's own message when loans will not load", async () => {
    forUser.mockRejectedValue(new ApiError(500, 'Loans are unavailable.'));
    renderLoans();

    expect(await screen.findByText('Loans are unavailable.')).toBeInTheDocument();
  });

  it('retries loans when asked', async () => {
    const user = userEvent.setup();
    forUser.mockRejectedValueOnce(new ApiError(500, 'Not this time.'));
    renderLoans();
    await screen.findByText('Not this time.');

    forUser.mockResolvedValue(page([loan(1)]));
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    await waitFor(() => expect(screen.getByText('Book 1')).toBeInTheDocument());
  });

  it('keeps the fines list usable when the overdue list fails', async () => {
    fines.mockResolvedValue(
      page([loan(1, { status: 'RETURNED', returnDate: '2026-09-20', fineAmount: 1.05, finePaymentStatus: 'UNPAID' })]),
    );
    byStatus.mockRejectedValue(new ApiError(500, 'Overdue list is down.'));
    renderFines('ROLE_LIBRARIAN');

    // One section failing must not take the other with it.
    expect(await screen.findByText('Book 1')).toBeInTheDocument();
    expect(screen.getByText('Overdue list is down.')).toBeInTheDocument();
  });
});
