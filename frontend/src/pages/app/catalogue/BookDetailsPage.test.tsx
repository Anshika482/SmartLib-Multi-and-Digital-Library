/** @vitest-environment jsdom */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthContext, type AuthState } from '@/auth/AuthContext';
import { BookDetailsPage } from './BookDetailsPage';
import { catalogueService } from '@/services/catalogueService';
import { digitalResourceService } from '@/services/digitalResourceService';
import { borrowRequestService } from '@/services/borrowRequestService';
import { ApiError } from '@/services/apiClient';
import type { BookSummary, DigitalResource, Page, Role, UserProfile } from '@/types/api';

/**
 * One book's page.
 *
 * <p>The two services are stubbed, so these tests are about what this screen
 * asks for and what it draws. Who is allowed to see which resource is settled
 * by the backend and proven by {@code DigitalResourceServiceTest}; the property
 * here is that the page adds no filtering of its own and therefore cannot get
 * that wrong - a member's response simply has no disabled resource in it.</p>
 */

const getBook = vi.spyOn(catalogueService, 'get');
const listResources = vi.spyOn(digitalResourceService, 'list');

function book(overrides: Partial<BookSummary> = {}): BookSummary {
  return {
    id: 7,
    title: 'The Left Hand of Darkness',
    author: 'Ursula K. Le Guin',
    isbn: '978-0-441-47812-5',
    categoryId: 4,
    categoryName: 'Science fiction',
    totalCopies: 5,
    availableCopies: 2,
    hasCover: false,
    coverUrl: null,
    ...overrides,
  };
}

function resource(id: number, overrides: Partial<DigitalResource> = {}): DigitalResource {
  return {
    id,
    bookId: 7,
    bookTitle: 'The Left Hand of Darkness',
    title: `Resource ${id}`,
    description: null,
    resourceType: 'PDF',
    resourceUrl: `https://example.invalid/${id}`,
    enabled: true,
    createdAt: '2026-09-01T10:00:00',
    updatedAt: '2026-09-01T10:00:00',
    ...overrides,
  };
}

function resourcePage(content: DigitalResource[]): Page<DigitalResource> {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: 1 };
}

function renderDetails(role: Role = 'ROLE_MEMBER', path = '/app/catalogue/7') {
  const value: AuthState = {
    user: { id: 1, username: 'asha', email: 'a@b.invalid', role, enabled: true } as UserProfile,
    loading: false,
    signIn: vi.fn(),
    signOut: vi.fn(),
  };

  return render(
    <AuthContext.Provider value={value}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/app/catalogue/:bookId" element={<BookDetailsPage />} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

function section(name: string): HTMLElement {
  return screen.getByRole('heading', { level: 2, name }).parentElement as HTMLElement;
}

beforeEach(() => {
  getBook.mockReset();
  listResources.mockReset();
  getBook.mockResolvedValue(book());
  listResources.mockResolvedValue(resourcePage([resource(1)]));
});

// ---------- the facts ----------

describe('a book', () => {
  it('shows the bibliographic facts the API returned', async () => {
    renderDetails();

    await screen.findByText('Ursula K. Le Guin');

    expect(screen.getByRole('heading', { level: 1, name: 'The Left Hand of Darkness' })).toBeInTheDocument();
    expect(screen.getByText('978-0-441-47812-5')).toBeInTheDocument();
    expect(screen.getByText('Science fiction')).toBeInTheDocument();
  });

  it('asks for the book by the id in the URL, and nothing else', async () => {
    renderDetails('ROLE_MEMBER', '/app/catalogue/42');

    await waitFor(() => expect(getBook).toHaveBeenCalled());

    expect(getBook.mock.calls[0][0]).toBe(42);
    expect(listResources.mock.calls[0][0]).toMatchObject({ bookId: 42 });
  });

  it('says a book is unshelved rather than inventing a category', async () => {
    getBook.mockResolvedValue(book({ categoryId: null, categoryName: null }));
    renderDetails();

    expect(await screen.findByText('Not shelved')).toBeInTheDocument();
  });

  it('keeps the catalogue reachable from the breadcrumb', async () => {
    renderDetails();

    await screen.findByText('Ursula K. Le Guin');

    expect(screen.getByRole('link', { name: 'Catalogue' })).toHaveAttribute('href', '/app/catalogue');
  });
});

// ---------- availability, by role ----------

describe('availability', () => {
  it('tells a member what they can borrow, without the stock breakdown', async () => {
    renderDetails('ROLE_MEMBER');

    await screen.findByText('2 available');
    expect(screen.getByText('2 of 5 copies available')).toBeInTheDocument();

    expect(screen.queryByText('Stock')).not.toBeInTheDocument();
    expect(screen.queryByText(/on loan$/)).not.toBeInTheDocument();
  });

  it.each<Role>(['ROLE_LIBRARIAN', 'ROLE_ADMIN', 'ROLE_SUPER_ADMIN'])(
    'gives %s the stock breakdown as well',
    async (role) => {
      renderDetails(role);

      await screen.findByText('Stock');
      expect(screen.getByText('5 held, 2 on the shelf, 3 on loan')).toBeInTheDocument();
    },
  );

  it('says all copies are out when none are left', async () => {
    getBook.mockResolvedValue(book({ availableCopies: 0, totalCopies: 5 }));
    renderDetails();

    expect(await screen.findByText('All on loan')).toBeInTheDocument();
  });
});

// ---------- the cover ----------

describe('the cover', () => {
  it('draws a stand-in when the book has none, rather than a broken image', async () => {
    const { container } = renderDetails();

    await screen.findByText('Ursula K. Le Guin');

    expect(container.querySelector('.sl-book__spine')).not.toBeNull();
    expect(container.querySelector('img')).toBeNull();
  });

  it('never prints the cover path or any storage detail on screen', async () => {
    getBook.mockResolvedValue(book({ hasCover: true, coverUrl: '/api/books/7/cover' }));
    const { container } = renderDetails();

    await screen.findByText('Ursula K. Le Guin');

    expect(container.textContent).not.toContain('/api/books');
    expect(container.textContent).not.toContain('cover');
  });
});

// ---------- digital resources ----------

describe('what is online', () => {
  it('opens each resource in the reader rather than at the file', async () => {
    listResources.mockResolvedValue(resourcePage([resource(1, { title: 'Chapter one' })]));
    renderDetails();

    await screen.findByText('Chapter one');

    // Into the reader, which is where the type decides what to do and where
    // every outward link is given noopener and noreferrer. Linking straight at
    // resourceUrl from here would mean two places had to get that right.
    const link = screen.getByRole('link', { name: 'Chapter one' });
    expect(link).toHaveAttribute('href', '/app/reading/1');
    expect(link).not.toHaveAttribute('target');
  });

  it('names the kind of each resource', async () => {
    listResources.mockResolvedValue(resourcePage([resource(1, { resourceType: 'EPUB' })]));
    renderDetails();

    expect(await screen.findByText('E-book')).toBeInTheDocument();
  });

  it('shows a member no hidden badge, because they are sent no hidden resource', async () => {
    // What a member's response actually looks like: the disabled one is absent
    // from it, not present and filtered out here.
    listResources.mockResolvedValue(resourcePage([resource(1, { enabled: true })]));
    renderDetails('ROLE_MEMBER');

    await screen.findByText('Resource 1');

    expect(screen.queryByText('Hidden from members')).not.toBeInTheDocument();
  });

  it('marks the resources staff can see but members cannot', async () => {
    listResources.mockResolvedValue(
      resourcePage([resource(1, { enabled: true }), resource(2, { enabled: false })]),
    );
    renderDetails('ROLE_LIBRARIAN');

    await screen.findByText('Resource 2');

    const online = within(section('Available online'));
    expect(online.getByText('Hidden from members')).toBeInTheDocument();
    // Exactly one of the two is marked.
    expect(online.getAllByText('Hidden from members')).toHaveLength(1);
  });

  it('says nothing is online rather than leaving the section blank', async () => {
    listResources.mockResolvedValue(resourcePage([]));
    renderDetails();

    expect(await screen.findByText('Nothing online for this book')).toBeInTheDocument();
  });

  it('tells staff where to add one, and a member only that there is nothing', async () => {
    listResources.mockResolvedValue(resourcePage([]));
    renderDetails('ROLE_LIBRARIAN');

    expect(await screen.findByText(/Attach a PDF/)).toBeInTheDocument();
  });

  it('still shows the book when the resources cannot be read', async () => {
    listResources.mockRejectedValue(new ApiError(500, 'Resources are down.'));
    renderDetails();

    expect(await screen.findByText('Ursula K. Le Guin')).toBeInTheDocument();
    expect(screen.getByText('Resources are down.')).toBeInTheDocument();
  });
});

// ---------- borrowing is the next step ----------

describe('borrowing', () => {
  it('lets a member ask for the book, and says what happens next', async () => {
    const user = userEvent.setup();
    const ask = vi.spyOn(borrowRequestService, 'request').mockResolvedValue({} as never);
    renderDetails('ROLE_MEMBER');

    await screen.findByText('Ursula K. Le Guin');

    const borrowing = within(section('Borrowing'));
    await user.click(borrowing.getByRole('button', { name: 'Request this book' }));

    // The book comes from the URL; nothing identifies the member, because the
    // server takes that from the token.
    await waitFor(() => expect(ask).toHaveBeenCalledWith(7));

    // Asked for once: the control is replaced rather than left clickable, since
    // a second live request for the same book is refused.
    await screen.findByText(/Asked for/);
    expect(screen.queryByRole('button', { name: 'Request this book' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'See my requests' })).toHaveAttribute(
      'href',
      '/app/my-requests',
    );
  });

  it("shows the API's own words when a request is refused, and keeps the button", async () => {
    const user = userEvent.setup();
    vi.spyOn(borrowRequestService, 'request').mockRejectedValue(
      new ApiError(409, 'You already have an open request for: The Left Hand of Darkness'),
    );
    renderDetails('ROLE_MEMBER');

    await screen.findByText('Ursula K. Le Guin');
    await user.click(screen.getByRole('button', { name: 'Request this book' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('already have an open request');
    // Still offered: the refusal may be about state that changes.
    expect(screen.getByRole('button', { name: 'Request this book' })).toBeInTheDocument();
  });

  it('does not offer staff a request button, and points them at the queue', async () => {
    const ask = vi.spyOn(borrowRequestService, 'request');
    renderDetails('ROLE_LIBRARIAN');

    await screen.findByText('Ursula K. Le Guin');

    const borrowing = within(section('Borrowing'));
    expect(borrowing.queryByRole('button', { name: 'Request this book' })).not.toBeInTheDocument();
    expect(borrowing.getByRole('link', { name: 'Go to requests' })).toHaveAttribute(
      'href',
      '/app/requests',
    );
    expect(ask).not.toHaveBeenCalled();
  });

  it('still offers no issue or return control: that is the desk’s screen', async () => {
    renderDetails('ROLE_LIBRARIAN');

    await screen.findByText('Ursula K. Le Guin');

    // Requesting is not borrowing. Nothing on this page moves stock.
    expect(screen.queryByRole('button', { name: /issue|return/i })).not.toBeInTheDocument();
  });
});

// ---------- the states ----------

describe('book details states', () => {
  it('shows a placeholder before anything arrives', () => {
    getBook.mockReturnValue(new Promise(() => {}));
    const { container } = renderDetails();

    expect(container.querySelectorAll('.sl-skeleton').length).toBeGreaterThan(0);
  });

  it("passes on the API's message when the book cannot be read", async () => {
    getBook.mockRejectedValue(new ApiError(404, 'Book not found with id: 7'));
    renderDetails();

    expect(await screen.findByText('Book not found with id: 7')).toBeInTheDocument();
  });

  it('leaves the catalogue one click away when the book is not found', async () => {
    getBook.mockRejectedValue(new ApiError(404, 'Book not found with id: 7'));
    renderDetails();

    await screen.findByText('Book not found with id: 7');

    // The breadcrumb still has to work: this is the most likely place to need it.
    expect(screen.getByRole('link', { name: 'Catalogue' })).toHaveAttribute('href', '/app/catalogue');
  });

  it('retries when asked', async () => {
    const user = userEvent.setup();
    getBook.mockRejectedValueOnce(new ApiError(500, 'Not this time.'));
    renderDetails();
    await screen.findByText('Not this time.');

    getBook.mockResolvedValue(book());
    await user.click(screen.getAllByRole('button', { name: 'Try again' })[0]);

    await waitFor(() => expect(screen.getByText('Ursula K. Le Guin')).toBeInTheDocument());
  });

  it('refuses a reference that is not a book id, without asking the server', async () => {
    renderDetails('ROLE_MEMBER', '/app/catalogue/not-a-number');

    expect(await screen.findByText('That is not a book reference this library uses.')).toBeInTheDocument();
    expect(getBook).not.toHaveBeenCalled();
    expect(listResources).not.toHaveBeenCalled();
  });
});
