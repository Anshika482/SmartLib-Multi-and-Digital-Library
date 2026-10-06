import type { BookSummary } from '@/types/api';
import { Badge } from '@/components/ui/Badge';
import { availabilityOf } from './availability';
import { BookCover } from './BookCover';
import './BookCard.css';

/**
 * One book, as a card.
 *
 * <p>Everything shown comes from the row the API returned - title, author,
 * category and the two copy counts. There is no rating and no blurb, because
 * the backend stores neither and a placeholder that looked like real editorial
 * copy would be a small lie repeated across the page.</p>
 *
 * <p>A book with a cover shows it. One without shows its own initial on a
 * drawn spine - clearly a graphic rather than a photograph of an edition
 * nobody has - which is what every book showed before covers existed.</p>
 */
export function BookCard({ book }: { book: BookSummary }) {
  const availability = availabilityOf(book);

  return (
    <article className="sl-book sl-panel" aria-labelledby={`book-${book.id}-title`}>
      <div className="sl-book__cover" aria-hidden="true">
        <BookCover book={book} />
      </div>

      <div className="sl-book__body">
        <h3 className="sl-book__title" id={`book-${book.id}-title`} title={book.title}>
          {book.title}
        </h3>
        <p className="sl-book__author">{book.author}</p>

        <div className="sl-book__meta">
          <Badge tone={availability.tone} srLabel={availability.detail}>
            {availability.label}
          </Badge>
          {book.categoryName !== null && book.categoryName.length > 0 && (
            <span className="sl-book__category" title={book.categoryName}>
              {book.categoryName}
            </span>
          )}
        </div>
      </div>
    </article>
  );
}
