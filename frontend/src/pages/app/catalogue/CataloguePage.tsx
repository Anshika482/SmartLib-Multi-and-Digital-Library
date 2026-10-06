import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { PageHeading } from '../PageHeading';
import { BookCard } from '@/components/catalogue/BookCard';
import { Button } from '@/components/ui/Button';
import { DataState } from '@/components/ui/DataState';
import { Skeleton } from '@/components/ui/Skeleton';
import { useAsync } from '@/hooks/useAsync';
import { catalogueService } from '@/services/catalogueService';
import { CATEGORY_PAGE_MAX, categoryService } from '@/services/categoryService';
import type { BookSummary, Category, Page } from '@/types/api';
import './CataloguePage.css';

/** A gridful. Twelve divides by two, three and four, so no row is left ragged. */
const PAGE_SIZE = 12;

/**
 * The library's catalogue: everything it holds, searchable.
 *
 * <p><b>The filters live in the URL.</b> A search is then a place rather than a
 * state - it survives a reload, it can be sent to somebody, and the browser's
 * back button walks back through it. Opening a book and returning lands on the
 * same page of the same search instead of at the top of an unfiltered list.</p>
 *
 * <p><b>The backend does the filtering.</b> Keyword and category go to
 * {@code GET /api/books} as parameters and the totals that come back describe
 * the filtered set. Nothing is fetched and then narrowed here, which is what
 * keeps the paging honest: page 3 of a search is the server's page 3.</p>
 *
 * <p>Which books exist is not this screen's decision either. The endpoint
 * answers with the caller's own library and nothing else, so there is no
 * library to pass and no way for this page to ask for another one.</p>
 */
export function CataloguePage() {
  const [params, setParams] = useSearchParams();

  const keyword = params.get('q') ?? '';
  const categoryParam = params.get('category');
  const categoryId = categoryParam === null ? undefined : Number(categoryParam);
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  // A category id that is not a number is treated as no filter rather than sent
  // to the backend as nonsense.
  const category = categoryId !== undefined && Number.isInteger(categoryId) ? categoryId : undefined;

  const filtered = keyword.trim().length > 0 || category !== undefined;

  const books = useAsync<Page<BookSummary>>(
    (signal) =>
      catalogueService.list(
        {
          page,
          size: PAGE_SIZE,
          sortBy: 'title',
          direction: 'asc',
          keyword: keyword.trim().length > 0 ? keyword : undefined,
          categoryId: category,
        },
        signal,
      ),
    [keyword, category, page],
  );

  const categories = useAsync<Page<Category>>((signal) => categoryService.list(signal), []);

  /** Replaces the query, and returns to the first page unless the page is what changed. */
  function apply(changes: { q?: string; category?: string | null; page?: number }) {
    const next = new URLSearchParams(params);

    if (changes.q !== undefined) {
      if (changes.q.trim().length > 0) {
        next.set('q', changes.q.trim());
      } else {
        next.delete('q');
      }
    }

    if (changes.category !== undefined) {
      if (changes.category === null || changes.category === '') {
        next.delete('category');
      } else {
        next.set('category', changes.category);
      }
    }

    if (changes.page !== undefined && changes.page > 0) {
      next.set('page', String(changes.page));
    } else {
      // Page 0 is the default, so it is left out rather than spelled out.
      next.delete('page');
    }

    setParams(next);
  }

  const shown = books.data?.content ?? null;
  const total = books.data?.totalElements ?? 0;
  const totalPages = books.data?.totalPages ?? 0;

  return (
    <>
      <PageHeading
        title="Catalogue"
        lead={
          books.data === null
            ? 'Everything your library holds.'
            : `${total.toLocaleString()} ${total === 1 ? 'title' : 'titles'}${
                filtered ? ' match your search' : ' in your library'
              }.`
        }
      />

      <CatalogueFilters
        keyword={keyword}
        category={categoryParam ?? ''}
        categories={categories.data}
        filtered={filtered}
        onApply={apply}
      />

      <DataState
        data={shown}
        loading={books.loading}
        error={books.error}
        onRetry={books.reload}
        label="Catalogue results"
        emptyTitle={filtered ? 'Nothing matched' : 'No books catalogued yet'}
        emptyDetail={
          filtered
            ? 'Try a different word, or clear the filters to see everything.'
            : 'Once a librarian adds the first title, it will appear here.'
        }
        skeleton={
          <ul className="sl-books">
            {Array.from({ length: 8 }, (_, slot) => (
              <li className="sl-panel sl-books__placeholder" key={slot}>
                <Skeleton height="7rem" radius="var(--radius-md)" />
                <Skeleton height="1.1rem" width="85%" />
                <Skeleton height="0.9rem" width="55%" />
              </li>
            ))}
          </ul>
        }
      >
        {(items) => (
          <ul className="sl-books">
            {items.map((book) => (
              <li key={book.id}>
                {/* The whole card is the link, so the target is as large as it
                    looks. The card holds no control of its own, which is what
                    makes that legal as well as convenient. */}
                <Link className="sl-book__link" to={`/app/catalogue/${book.id}`}>
                  <BookCard book={book} />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </DataState>

      {totalPages > 1 && (
        <Pager page={page} totalPages={totalPages} onGo={(next) => apply({ page: next })} />
      )}
    </>
  );
}

/**
 * The search box and the shelf filter.
 *
 * <p>The keyword is a form: it applies on submit rather than on every
 * keystroke, so a request is not sent for each letter and the URL does not
 * collect a history entry per character.</p>
 */
function CatalogueFilters({
  keyword,
  category,
  categories,
  filtered,
  onApply,
}: {
  keyword: string;
  category: string;
  categories: Page<Category> | null;
  filtered: boolean;
  onApply: (changes: { q?: string; category?: string | null; page?: number }) => void;
}) {
  const [draft, setDraft] = useState(keyword);

  // The URL is the source of truth, so a keyword arriving from elsewhere - the
  // back button, a shared link, the reset below - is reflected in the box.
  useEffect(() => setDraft(keyword), [keyword]);

  const shelves = categories?.content ?? [];
  const more = (categories?.totalElements ?? 0) > CATEGORY_PAGE_MAX;

  return (
    <form
      className="sl-filters sl-panel"
      role="search"
      onSubmit={(event) => {
        event.preventDefault();
        onApply({ q: draft, page: 0 });
      }}
    >
      <div className="sl-filters__field">
        <label className="sl-filters__label" htmlFor="catalogue-keyword">
          Search
        </label>
        <input
          className="sl-filters__input"
          id="catalogue-keyword"
          type="search"
          value={draft}
          placeholder="Title, author or ISBN"
          onChange={(event) => setDraft(event.target.value)}
        />
      </div>

      <div className="sl-filters__field">
        <label className="sl-filters__label" htmlFor="catalogue-category">
          Category
        </label>
        <select
          className="sl-filters__input"
          id="catalogue-category"
          value={category}
          onChange={(event) => onApply({ category: event.target.value, page: 0 })}
        >
          <option value="">All categories</option>
          {shelves.map((shelf) => (
            <option key={shelf.id} value={String(shelf.id)}>
              {shelf.name}
            </option>
          ))}
          {more && (
            // Said plainly rather than silently truncated: the endpoint returns
            // at most fifty, and a shelf missing from this list is still
            // reachable by searching.
            <option value="" disabled>
              More shelves exist - search by name
            </option>
          )}
        </select>
      </div>

      <div className="sl-filters__actions">
        <Button type="submit">Search</Button>
        {filtered && (
          <Button type="button" variant="ghost" onClick={() => onApply({ q: '', category: null, page: 0 })}>
            Clear filters
          </Button>
        )}
      </div>
    </form>
  );
}

/**
 * Previous, next, and where you are.
 *
 * <p>Page numbers are shown one-based because that is how people count, while
 * everything sent to the backend stays zero-based.</p>
 */
function Pager({
  page,
  totalPages,
  onGo,
}: {
  page: number;
  totalPages: number;
  onGo: (page: number) => void;
}) {
  return (
    <nav className="sl-pager" aria-label="Catalogue pages">
      <Button variant="ghost" disabled={page <= 0} onClick={() => onGo(page - 1)}>
        Previous
      </Button>

      <p className="sl-pager__state" aria-live="polite">
        Page {page + 1} of {totalPages}
      </p>

      <Button variant="ghost" disabled={page >= totalPages - 1} onClick={() => onGo(page + 1)}>
        Next
      </Button>
    </nav>
  );
}
