import { Section } from '@/components/ui/Section';
import { DataState } from '@/components/ui/DataState';
import { Skeleton } from '@/components/ui/Skeleton';
import { BookCard } from '@/components/catalogue/BookCard';
import { SignInPrompt } from '@/components/ui/SignInPrompt';
import type { BookSummary, Page } from '@/types/api';
import type { AsyncState } from '@/hooks/useAsync';

/**
 * What is on the shelf, from the shelf.
 *
 * <p>The books are the first page of the real catalogue, sorted so the ones
 * with copies available come first - a useful ordering rather than an
 * editorial one, since the backend has no notion of a featured title and
 * inventing one would mean choosing books for a library this code has never
 * seen.</p>
 *
 * <p>The page it renders is fetched by the home page and passed in, because
 * the hero shows the same response's total. One request, two places.</p>
 *
 * <p>Signed out there is nothing to show: the catalogue endpoint requires a
 * token, and this library's shelves are not public. The section says that and
 * points at the sign-in page.</p>
 */
export function FeaturedBooks({
  books,
  signedIn,
}: {
  books: AsyncState<Page<BookSummary>>;
  signedIn: boolean;
}) {
  const shown = books.data?.content ?? null;
  const total = books.data?.totalElements ?? 0;

  return (
    <Section
      id="catalogue"
      eyebrow="On the shelves"
      title="Available in your library now"
      lead="Straight from your catalogue, with copies on hand listed first."
      aside={
        signedIn && total > 0 ? (
          <p className="sl-section__lead">
            Showing {shown?.length ?? 0} of {total.toLocaleString()}
          </p>
        ) : undefined
      }
    >
      {!signedIn ? (
        <SignInPrompt
          title="Sign in to see the shelves"
          detail="A library's catalogue is only visible to its own members and staff."
        />
      ) : (
      <DataState
        data={shown}
        loading={books.loading}
        error={books.error}
        onRetry={books.reload}
        label="Books available in your library"
        emptyTitle="No books catalogued yet"
        emptyDetail="Once a librarian adds the first title, it will appear here."
        skeleton={
          <ul className="sl-books">
            {[0, 1, 2, 3].map((slot) => (
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
                <BookCard book={book} />
              </li>
            ))}
          </ul>
        )}
      </DataState>
      )}
    </Section>
  );
}
