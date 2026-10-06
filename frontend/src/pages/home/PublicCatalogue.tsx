import { useCallback, useState, type FormEvent } from 'react';
import { Section } from '@/components/ui/Section';
import { DataState } from '@/components/ui/DataState';
import { Skeleton } from '@/components/ui/Skeleton';
import { Button } from '@/components/ui/Button';
import { TextField } from '@/components/ui/TextField';
import { PublicBookCard } from '@/components/public/PublicBookCard';
import { publicCatalogueService, MAX_PUBLIC_PAGE_SIZE } from '@/services/publicCatalogueService';
import { useAsync } from '@/hooks/useAsync';
import type { Page, PublicBook } from '@/types/api';
import './PublicCatalogue.css';

const PAGE_SIZE = 12;

/**
 * The part of SmartLib a visitor can actually use before deciding anything.
 *
 * <p>A real search against a real catalogue - `GET /api/public/catalogue`,
 * which needs no token and returns bibliographic facts only. Nothing here is
 * sample data: an empty result means the libraries on this deployment have
 * catalogued nothing matching, and it says so rather than showing invented
 * titles to fill the grid.</p>
 *
 * <p>The search is submitted rather than fired per keystroke. One request per
 * intent keeps an unauthenticated endpoint from being hammered by typing, and
 * makes the loading state meaningful.</p>
 */
export function PublicCatalogue() {
  const [term, setTerm] = useState('');
  const [submitted, setSubmitted] = useState('');
  const [size, setSize] = useState(PAGE_SIZE);

  const books = useAsync<Page<PublicBook>>(
    (signal) => publicCatalogueService.search({ keyword: submitted, page: 0, size }, signal),
    [submitted, size],
  );

  const onSubmit = useCallback(
    (event: FormEvent<HTMLFormElement>) => {
      event.preventDefault();
      setSize(PAGE_SIZE);
      setSubmitted(term);
    },
    [term],
  );

  const shown = books.data?.content ?? null;
  const total = books.data?.totalElements ?? 0;
  const hasMore = shown !== null && shown.length < total && size < MAX_PUBLIC_PAGE_SIZE;

  return (
    <Section
      id="catalogue"
      eyebrow="Browse the shelves"
      title="Search the catalogue before you sign in"
      lead="Titles, authors and the library that holds them - open to anyone, exactly as a library's catalogue has always been. What a copy costs, who has it out and everything else stays private."
    >
      <form className="sl-explore__search" onSubmit={onSubmit} role="search">
        <div className="sl-explore__field">
          <TextField
            label="Search by title, author or ISBN"
            name="catalogue-search"
            value={term}
            onChange={(event) => setTerm(event.target.value)}
            placeholder="e.g. algorithms"
          />
        </div>
        <Button type="submit" busy={books.loading}>
          {books.loading ? 'Searching...' : 'Search'}
        </Button>
        <p className="sl-explore__hint">
          Searching every library on this SmartLib deployment.
        </p>
      </form>

      {!books.loading && books.error === null && total > 0 && (
        <p className="sl-explore__count">
          {total.toLocaleString()} {total === 1 ? 'title' : 'titles'}
          {submitted.trim().length > 0 ? ` matching "${submitted.trim()}"` : ' catalogued'}
        </p>
      )}

      <DataState
        data={shown}
        loading={books.loading}
        error={books.error}
        onRetry={books.reload}
        label="Public catalogue results"
        emptyTitle={submitted.trim().length > 0 ? 'Nothing matched that' : 'Nothing catalogued yet'}
        emptyDetail={
          submitted.trim().length > 0
            ? 'Try a different title, author or ISBN.'
            : 'No library on this deployment has catalogued a title yet.'
        }
        skeleton={
          <ul className="sl-explore__results">
            {[0, 1, 2, 3, 4, 5].map((slot) => (
              <li className="sl-panel sl-explore__placeholder" key={slot}>
                <Skeleton height="1.2rem" width="80%" />
                <Skeleton height="0.9rem" width="50%" />
                <Skeleton height="0.8rem" width="65%" />
              </li>
            ))}
          </ul>
        }
      >
        {(items) => (
          <>
            <ul className="sl-explore__results">
              {items.map((book) => (
                <li key={`${book.libraryName ?? ''}-${book.isbn}-${book.title}`}>
                  <PublicBookCard book={book} />
                </li>
              ))}
            </ul>

            {!hasMore && shown !== null && shown.length < total && (
              <p className="sl-explore__count sl-explore__more">
                Showing the first {shown.length} of {total.toLocaleString()}. Sign in to search your own
                library in full.
              </p>
            )}

            {hasMore && (
              <div className="sl-explore__more">
                <Button
                  variant="ghost"
                  onClick={() =>
                    setSize((current) => Math.min(current + PAGE_SIZE, MAX_PUBLIC_PAGE_SIZE))
                  }
                >
                  Show more
                </Button>
              </div>
            )}
          </>
        )}
      </DataState>
    </Section>
  );
}
