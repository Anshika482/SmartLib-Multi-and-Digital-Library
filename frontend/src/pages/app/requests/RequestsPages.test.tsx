/** @vitest-environment jsdom */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { AuthContext, type AuthState } from '@/auth/AuthContext';
import { MyRequestsPage } from './MyRequestsPage';
import { StaffRequestsPage } from './StaffRequestsPage';
import { borrowRequestService } from '@/services/borrowRequestService';
import { ApiError } from '@/services/apiClient';
import type { BorrowRequest, BorrowRequestStatus, Page, Role, UserProfile } from '@/types/api';

/**
 * The two request screens.
 *
 * <p>The service is stubbed, so these are about the calls each screen makes and
 * what it draws. Whether the server would allow any of them is settled by
 * {@code BorrowRequestWorkflowIntegrationTest} against a real database and a
 * real filter chain.</p>
 *
 * <p>Two properties are worth pinning hardest. <b>Approving is not issuing</b>:
 * a waiting request offers no way to hand a copy over, and an approved one
 * offers a separate action that carries a due date. And <b>nothing is guessed
 * after an action</b> - every one reloads, because the server is the only thing
 * that knows what the request is now.</p>
 */

const mine = vi.spyOn(borrowRequestService, 'mine');
const queue = vi.spyOn(borrowRequestService, 'queue');
const cancel = vi.spyOn(borrowRequestService, 'cancel');
const approve = vi.spyOn(borrowRequestService, 'approve');
const reject = vi.spyOn(borrowRequestService, 'reject');
const issue = vi.spyOn(borrowRequestService, 'issue');

function request(id: number, overrides: Partial<BorrowRequest> = {}): BorrowRequest {
  return {
    id,
    bookId: 100 + id,
    bookTitle: `Book ${id}`,
    bookAuthor: `Author ${id}`,
    memberName: null,
    status: 'REQUESTED',
    requestedAt: '2026-09-20T10:00:00',
    decidedAt: null,
    transactionId: null,
    ...overrides,
  };
}

function page(content: BorrowRequest[]): Page<BorrowRequest> {
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

const renderMine = () => renderPage(<MyRequestsPage />, 'ROLE_MEMBER');
const renderQueue = (role: Role = 'ROLE_LIBRARIAN') => renderPage(<StaffRequestsPage />, role);

function row(title: string): HTMLElement {
  return screen.getByText(title).closest('li') as HTMLElement;
}

beforeEach(() => {
  [mine, queue, cancel, approve, reject, issue].forEach((spy) => spy.mockReset());
  mine.mockResolvedValue(page([request(1)]));
  queue.mockResolvedValue(page([request(1)]));
});

// ========== the member ==========

describe('a member’s own requests', () => {
  it('asks only for their own list, with no id to substitute', async () => {
    renderMine();

    await screen.findByText('Book 1');

    expect(mine).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(mine.mock.calls[0][0] ?? {})).not.toContain('user');
  });

  it('links each request to the book it is for', async () => {
    renderMine();

    await screen.findByText('Book 1');

    expect(screen.getByRole('link', { name: 'Book 1' })).toHaveAttribute('href', '/app/catalogue/101');
  });

  it('names the state in words, not only a colour', async () => {
    mine.mockResolvedValue(page([request(1, { status: 'APPROVED' })]));
    renderMine();

    expect(await screen.findByText('Ready to collect')).toBeInTheDocument();
    expect(screen.getByText('Collect it at the desk.')).toBeInTheDocument();
  });

  it.each<[BorrowRequestStatus, boolean]>([
    ['REQUESTED', true],
    ['APPROVED', true],
    ['REJECTED', false],
    ['CANCELLED', false],
    ['FULFILLED', false],
  ])('offers withdraw for %s: %s', async (status, offered) => {
    mine.mockResolvedValue(page([request(1, { status })]));
    renderMine();

    await screen.findByText('Book 1');

    const withdraw = screen.queryByRole('button', { name: 'Withdraw' });
    expect(withdraw === null).toBe(!offered);
  });

  it('withdraws, then reloads rather than assuming what happened', async () => {
    const user = userEvent.setup();
    cancel.mockResolvedValue(request(1, { status: 'CANCELLED' }));
    renderMine();
    await screen.findByText('Book 1');

    await user.click(screen.getByRole('button', { name: 'Withdraw' }));

    await waitFor(() => expect(cancel).toHaveBeenCalledWith(1));
    // Reloaded: the server decides what the request is now.
    await waitFor(() => expect(mine).toHaveBeenCalledTimes(2));
  });

  it("shows the API's own words when a withdrawal is refused", async () => {
    const user = userEvent.setup();
    cancel.mockRejectedValue(new ApiError(409, 'Cannot cancel a request that is FULFILLED.'));
    renderMine();
    await screen.findByText('Book 1');

    await user.click(screen.getByRole('button', { name: 'Withdraw' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('FULFILLED');
  });
});

// ========== the desk ==========

describe('the staff queue', () => {
  it('opens on what is waiting, which is the working list', async () => {
    renderQueue();

    await screen.findByText('Book 1');

    expect(queue).toHaveBeenCalledWith('REQUESTED', expect.anything(), expect.anything());
  });

  it('switches queue when another tab is chosen', async () => {
    const user = userEvent.setup();
    renderQueue();
    await screen.findByText('Book 1');

    await user.click(screen.getByRole('tab', { name: 'Ready to collect' }));

    await waitFor(() =>
      expect(queue).toHaveBeenLastCalledWith('APPROVED', expect.anything(), expect.anything()),
    );
  });

  it('says who asked, which a member is never told', async () => {
    queue.mockResolvedValue(page([request(1, { memberName: 'Ravi Kumar' })]));
    renderQueue();

    expect(await screen.findByText('Ravi Kumar')).toBeInTheDocument();
  });

  // ---------- approval is not issue ----------

  it('offers approve and decline on a waiting request, and no way to issue it', async () => {
    renderQueue();
    await screen.findByText('Book 1');

    const actions = within(row('Book 1'));
    expect(actions.getByRole('button', { name: 'Approve' })).toBeInTheDocument();
    expect(actions.getByRole('button', { name: 'Decline' })).toBeInTheDocument();

    // The whole point of the two states: agreeing is not handing it over.
    expect(actions.queryByRole('button', { name: 'Issue the copy' })).not.toBeInTheDocument();
  });

  it('offers issuing only once a request is approved', async () => {
    queue.mockResolvedValue(page([request(1, { status: 'APPROVED' })]));
    renderQueue();
    await screen.findByText('Book 1');

    const actions = within(row('Book 1'));
    expect(actions.getByRole('button', { name: 'Issue the copy' })).toBeInTheDocument();
    expect(actions.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
  });

  it('approves and reloads', async () => {
    const user = userEvent.setup();
    approve.mockResolvedValue(request(1, { status: 'APPROVED' }));
    renderQueue();
    await screen.findByText('Book 1');

    await user.click(screen.getByRole('button', { name: 'Approve' }));

    await waitFor(() => expect(approve).toHaveBeenCalledWith(1));
    await waitFor(() => expect(queue).toHaveBeenCalledTimes(2));
  });

  it('declines and reloads', async () => {
    const user = userEvent.setup();
    reject.mockResolvedValue(request(1, { status: 'REJECTED' }));
    renderQueue();
    await screen.findByText('Book 1');

    await user.click(screen.getByRole('button', { name: 'Decline' }));

    await waitFor(() => expect(reject).toHaveBeenCalledWith(1));
  });

  it('issues with a due date, which staff can change first', async () => {
    const user = userEvent.setup();
    queue.mockResolvedValue(page([request(1, { status: 'APPROVED' })]));
    issue.mockResolvedValue({ id: 500 } as never);
    renderQueue();
    await screen.findByText('Book 1');

    const due = screen.getByLabelText('Due date for anything issued now');
    await user.clear(due);
    await user.type(due, '2026-12-31');

    await user.click(screen.getByRole('button', { name: 'Issue the copy' }));

    await waitFor(() => expect(issue).toHaveBeenCalledWith(1, '2026-12-31'));
  });

  it('shows which loan a collected request became', async () => {
    queue.mockResolvedValue(page([request(1, { status: 'FULFILLED', transactionId: 42 })]));
    renderQueue();

    expect(await screen.findByText('Issued as loan #42')).toBeInTheDocument();
  });

  it("shows the API's own words when the last copy has gone", async () => {
    const user = userEvent.setup();
    approve.mockRejectedValue(new ApiError(409, 'No copies available to issue for book: Book 1 (id 101)'));
    renderQueue();
    await screen.findByText('Book 1');

    await user.click(screen.getByRole('button', { name: 'Approve' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('No copies available');
  });

  it('refuses to fire two actions at once', async () => {
    const user = userEvent.setup();
    approve.mockReturnValue(new Promise(() => {}));
    renderQueue();
    await screen.findByText('Book 1');

    await user.click(screen.getByRole('button', { name: 'Approve' }));

    // While one is in flight the rest are disabled, so a double click cannot
    // approve and decline the same request.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Decline' })).toBeDisabled());
  });
});

// ========== states ==========

describe('request screen states', () => {
  it('shows a placeholder before anything arrives', () => {
    mine.mockReturnValue(new Promise(() => {}));
    const { container } = renderMine();

    expect(container.querySelectorAll('.sl-skeleton').length).toBeGreaterThan(0);
  });

  it('tells a member their list is empty without calling it an error', async () => {
    mine.mockResolvedValue(page([]));
    renderMine();

    expect(await screen.findByText('You have not asked for anything yet')).toBeInTheDocument();
  });

  it('tells staff a queue is empty in terms of that queue', async () => {
    queue.mockResolvedValue(page([]));
    renderQueue();

    expect(await screen.findByText('No member is waiting on a decision right now.')).toBeInTheDocument();
  });

  it('offers a retry when the list will not load', async () => {
    const user = userEvent.setup();
    mine.mockRejectedValueOnce(new ApiError(500, 'Not this time.'));
    renderMine();
    await screen.findByText('Not this time.');

    mine.mockResolvedValue(page([request(1)]));
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    await waitFor(() => expect(screen.getByText('Book 1')).toBeInTheDocument());
  });
});
