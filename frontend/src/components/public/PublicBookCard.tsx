import type { PublicBook } from '@/types/api';
import { Badge } from '@/components/ui/Badge';
import { LibrariesIcon } from '@/components/ui/icons';
import './PublicBookCard.css';

/**
 * A book in the public catalogue.
 *
 * <p>Shows exactly what the public endpoint returns and nothing more. There is
 * no availability badge here and no borrow action: whether a copy is on the
 * shelf is operational, the API does not send it to a stranger, and an action
 * a visitor cannot take would be a dead control.</p>
 */
export function PublicBookCard({ book }: { book: PublicBook }) {
  return (
    <article className="sl-pubbook sl-panel">
      <h3 className="sl-pubbook__title">{book.title}</h3>
      <p className="sl-pubbook__author">{book.author}</p>

      <div className="sl-pubbook__meta">
        {book.categoryName !== null && book.categoryName.length > 0 && (
          <Badge tone="accent" plain>
            {book.categoryName}
          </Badge>
        )}
        {book.libraryName !== null && book.libraryName.length > 0 && (
          <span className="sl-pubbook__library">
            <LibrariesIcon width="13" height="13" />
            {book.libraryName}
          </span>
        )}
        {book.isbn !== null && book.isbn.length > 0 && (
          <span className="sl-pubbook__isbn">ISBN {book.isbn}</span>
        )}
      </div>
    </article>
  );
}
