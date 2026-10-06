/** @vitest-environment jsdom */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { AuthContext, type AuthState } from '@/auth/AuthContext';
import { CataloguePage } from './CataloguePage';
import { catalogueService } from '@/services/catalogueService';
import { categoryService } from '@/services/categoryService';
import { ApiError } from '@/services/apiClient';
import type { BookSummary, Category, Page, Role, UserProfile } from '@/types/api';

/**
 * The catalogue screen.
 *
 * <p>The services are stubbed, so these tests are about the query this page
 * sends and what it does with the answer. That the backend filters correctly is
 * {@code CatalogueFilterIntegrationTest}'s job, against a real database.</p>
 *
 * <p>The property worth pinning hardest: <b>the page never filters</b>. It asks
 * for a page and renders what comes back. So each test checks the parameters
 * that went out, not that the right rows survived on the way in.</p>
 */

const list = vi.spyOn(catalogueService, 'list');
const categories = vi.spyOn(categoryService, 'list');

function book(id: number, overrides: Partial<BookSummary> = {}): BookSummary {
  return {
    id,
    title: `Title ${id}`,
    author: `Author ${id}`,
    isbn: `978-${id}`,
    categoryId: 4,
    categoryName: 'Fiction',
    totalCopies: 3,
    availableCopies: 2,
    hasCover: false,
    coverUrl: null,
    ...overrides,
  };
}

function page(content: BookSummary[], overrides: Partial<Page<BookSummary>> = {}): Page<BookSummary> {
  return {
    content,
    page: 0,
    size: 12,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
    ...overrides,
  };
}

function shelves(names: string[], totalElements = names.length): Page<Category> {
  return {
    content: names.map((name, index) => ({ id: index + 1, name })),
    page: 0,
    size: 50,
    totalElements,
    totalPages: 1,
  };
}

function renderCatalogue(role: Role = 'ROLE_MEMBER', url = '/app/catalogue') {
  const value: AuthState = {
    user: { id: 1, username: 'asha', email: 'a@b.invalid', role, enabled: true } as UserProfile,
    loading: false,
    signIn: vi.fn(),
    signOut: vi.fn(),
  };

  return render(
    <AuthContext.Provider value={value}>
      <MemoryRouter initialEntries={[url]}>
        <CataloguePage />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

/** The query of the most recent list call. */
function lastQuery() {
  return list.mock.calls[list.mock.calls.length - 1][0];
}

beforeEach(() => {
  list.mockReset();
  categories.mockReset();
  list.mockResolvedValue(page([book(1), book(2)]));
  categories.mockResolvedValue(shelves(['Fiction', 'History']));
});

// ---------- what it asks for ----------

describe('the first request', () => {
  it('asks for the first page, by title, with no filter', async () => {
    renderCatalogue();

    await screen.findByText('Title 1');

    expect(lastQuery()).toMatchObject({ page: 0, sortBy: 'title', direction: 'asc' });
    expect(lastQuery()?.keyword).toBeUndefined();
    expect(lastQuery()?.categoryId).toBeUndefined();
  });

  it('never sends a library: the backend takes that from the token', async () => {
    renderCatalogue();

    await screen.findByText('Title 1');

    expect(JSON.stringify(lastQuery() ?? {})).not.toContain('library');
  });
});

// ---------- search ----------

describe('search', () => {
  it('sends the keyword the reader typed, once they submit it', async () => {
    const user = userEvent.setup();
    renderCatalogue();
    await screen.findByText('Title 1');

    await user.type(screen.getByLabelText('Search'), 'dune');

    // Typing alone must not fire a request per keystroke.
    expect(lastQuery()?.keyword).toBeUndefined();

    await user.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() => expect(lastQuery()?.keyword).toBe('dune'));
  });

  it('reads a keyword out of the URL, so a search can be shared or reloaded', async () => {
    renderCatalogue('ROLE_MEMBER', '/app/catalogue?q=dune');

    await screen.findByText('Title 1');

    expect(lastQuery()?.keyword).toBe('dune');
    expect(screen.getByLabelText('Search')).toHaveValue('dune');
  });

  it('says how many matched rather than how many exist', async () => {
    list.mockResolvedValue(page([book(1)], { totalElements: 1 }));
    renderCatalogue('ROLE_MEMBER', '/app/catalogue?q=dune');

    expect(await screen.findByText(/1 title match your search/)).toBeInTheDocument();
  });
});

// ---------- category ----------

describe('the category filter', () => {
  it('offers the library’s own shelves', async () => {
    renderCatalogue();

    await screen.findByText('Title 1');

    const select = screen.getByLabelText('Category');
    expect(within(select).getByRole('option', { name: 'Fiction' })).toBeInTheDocument();
    expect(within(select).getByRole('option', { name: 'All categories' })).toBeInTheDocument();
  });

  it('sends the chosen shelf as an id and goes back to the first page', async () => {
    const user = userEvent.setup();
    renderCatalogue('ROLE_MEMBER', '/app/catalogue?page=3');
    await screen.findByText('Title 1');

    await user.selectOptions(screen.getByLabelText('Category'), '2');

    await waitFor(() => expect(lastQuery()?.categoryId).toBe(2));
    expect(lastQuery()?.page).toBe(0);
  });

  it('combines a keyword and a shelf in one request', async () => {
    renderCatalogue('ROLE_MEMBER', '/app/catalogue?q=dune&category=2');

    await screen.findByText('Title 1');

    expect(lastQuery()).toMatchObject({ keyword: 'dune', categoryId: 2 });
  });

  it('ignores a category that is not a number rather than sending it on', async () => {
    renderCatalogue('ROLE_MEMBER', '/app/catalogue?category=%27%20OR%201=1');

    await screen.findByText('Title 1');

    expect(lastQuery()?.categoryId).toBeUndefined();
  });

  it('says so when a library has more shelves than one page holds', async () => {
    categories.mockResolvedValue(shelves(['Fiction'], 120));
    renderCatalogue();

    await screen.findByText('Title 1');

    expect(screen.getByRole('option', { name: /More shelves exist/ })).toBeInTheDocument();
  });
});

// ---------- clearing ----------

describe('clearing the filters', () => {
  it('is offered only once something is filtered', async () => {
    renderCatalogue();
    await screen.findByText('Title 1');

    expect(screen.queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument();
  });

  it('drops the keyword and the shelf together', async () => {
    const user = userEvent.setup();
    renderCatalogue('ROLE_MEMBER', '/app/catalogue?q=dune&category=2&page=2');
    await screen.findByText('Title 1');

    await user.click(screen.getByRole('button', { name: 'Clear filters' }));

    await waitFor(() => expect(lastQuery()?.keyword).toBeUndefined());
    expect(lastQuery()?.categoryId).toBeUndefined();
    expect(lastQuery()?.page).toBe(0);
    expect(screen.getByLabelText('Search')).toHaveValue('');
  });
});

// ---------- pagination ----------

describe('pagination', () => {
  it('is not drawn when everything fits on one page', async () => {
    renderCatalogue();

    await screen.findByText('Title 1');

    expect(screen.queryByRole('button', { name: 'Next' })).not.toBeInTheDocument();
  });

  it('counts pages from one on screen and from zero in the request', async () => {
    list.mockResolvedValue(page([book(1)], { page: 0, totalElements: 40, totalPages: 4 }));
    renderCatalogue();

    expect(await screen.findByText('Page 1 of 4')).toBeInTheDocument();
    expect(lastQuery()?.page).toBe(0);
  });

  it('moves forward and back, and stops at both ends', async () => {
    const user = userEvent.setup();
    list.mockResolvedValue(page([book(1)], { totalElements: 40, totalPages: 4 }));
    renderCatalogue();
    await screen.findByText('Page 1 of 4');

    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(lastQuery()?.page).toBe(1));
  });

  it('keeps the filters while paging', async () => {
    const user = userEvent.setup();
    list.mockResolvedValue(page([book(1)], { totalElements: 40, totalPages: 4 }));
    renderCatalogue('ROLE_MEMBER', '/app/catalogue?q=dune&category=2');
    await screen.findByText('Page 1 of 4');

    await user.click(screen.getByRole('button', { name: 'Next' }));

    await waitFor(() => expect(lastQuery()?.page).toBe(1));
    expect(lastQuery()).toMatchObject({ keyword: 'dune', categoryId: 2 });
  });

  it('is on the last page when the URL says so', async () => {
    list.mockResolvedValue(page([book(1)], { page: 3, totalElements: 40, totalPages: 4 }));
    renderCatalogue('ROLE_MEMBER', '/app/catalogue?page=3');

    await screen.findByText('Page 4 of 4');

    expect(lastQuery()?.page).toBe(3);
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
  });
});

// ---------- the rows ----------

describe('the results', () => {
  it('opens each book at its own page', async () => {
    renderCatalogue();

    await screen.findByText('Title 1');

    expect(screen.getByRole('link', { name: /Title 1/ })).toHaveAttribute('href', '/app/catalogue/1');
    expect(screen.getByRole('link', { name: /Title 2/ })).toHaveAttribute('href', '/app/catalogue/2');
  });

  it('shows what the row says about availability, and invents nothing', async () => {
    list.mockResolvedValue(page([book(1, { availableCopies: 0, totalCopies: 3 })]));
    renderCatalogue();

    expect(await screen.findByText('All on loan')).toBeInTheDocument();
  });

  it('draws a stand-in for a book with no cover, and fetches one that has', async () => {
    list.mockResolvedValue(page([book(1, { hasCover: false, coverUrl: null })]));
    const { container } = renderCatalogue();

    await screen.findByText('Title 1');

    // The drawn spine, not a broken image.
    expect(container.querySelector('.sl-book__spine')).not.toBeNull();
    expect(container.querySelector('img')).toBeNull();
  });

  it('never prints an internal id on screen', async () => {
    list.mockResolvedValue(page([book(7, { categoryId: 99, categoryName: 'Fiction' })]));
    const { container } = renderCatalogue();

    await screen.findByText('Title 7');

    // The id belongs in the href, not in the text.
    expect(container.textContent).not.toContain('99');

    // The shelf is named, though - scoped to the card, since the filter offers
    // an option of the same name.
    const card = screen.getByRole('link', { name: /Title 7/ });
    expect(within(card).getByText('Fiction')).toBeInTheDocument();
  });
});

// ---------- the states ----------

describe('catalogue states', () => {
  it('shows a placeholder before anything arrives', () => {
    list.mockReturnValue(new Promise(() => {}));
    const { container } = renderCatalogue();

    expect(container.querySelectorAll('.sl-skeleton').length).toBeGreaterThan(0);
  });

  it("shows the API's own message and a way to try again", async () => {
    list.mockRejectedValue(new ApiError(500, 'The catalogue is having a moment.'));
    renderCatalogue();

    expect(await screen.findByText('The catalogue is having a moment.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('retries when asked', async () => {
    const user = userEvent.setup();
    list.mockRejectedValueOnce(new ApiError(500, 'Not this time.'));
    renderCatalogue();
    await screen.findByText('Not this time.');

    list.mockResolvedValue(page([book(1)]));
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    await waitFor(() => expect(screen.getByText('Title 1')).toBeInTheDocument());
  });

  it('says an empty library is empty, not that a search failed', async () => {
    list.mockResolvedValue(page([]));
    renderCatalogue();

    expect(await screen.findByText('No books catalogued yet')).toBeInTheDocument();
  });

  it('says a fruitless search differently, and offers a way out', async () => {
    list.mockResolvedValue(page([]));
    renderCatalogue('ROLE_MEMBER', '/app/catalogue?q=nothingmatchesthis');

    expect(await screen.findByText('Nothing matched')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeInTheDocument();
  });

  it('still renders the catalogue when the shelf list cannot be read', async () => {
    categories.mockRejectedValue(new ApiError(500, 'No categories today.'));
    renderCatalogue();

    // The filter is a convenience; losing it must not lose the books.
    expect(await screen.findByText('Title 1')).toBeInTheDocument();
    expect(screen.getByLabelText('Category')).toBeInTheDocument();
  });
});

// ---------- every role reads the same catalogue ----------

describe('roles', () => {
  it.each<Role>(['ROLE_MEMBER', 'ROLE_LIBRARIAN', 'ROLE_ADMIN', 'ROLE_SUPER_ADMIN'])(
    'sends the same request for %s, because the server decides what comes back',
    async (role) => {
      renderCatalogue(role);

      await screen.findByText('Title 1');

      expect(lastQuery()).toMatchObject({ page: 0, sortBy: 'title' });
      expect(JSON.stringify(lastQuery() ?? {})).not.toContain('role');
    },
  );
});
