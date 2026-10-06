import { useEffect, useState } from 'react';
import { apiObjectUrl } from '@/services/apiClient';
import type { BookSummary } from '@/types/api';

/**
 * A book's cover, or the drawn stand-in when it has none.
 *
 * <p>The cover endpoint sits behind the token, and an {@code <img src>} is a
 * plain browser request that carries no Authorization header. So the bytes are
 * fetched the way every other call is made and turned into an object URL, which
 * is released when this unmounts - an object URL holds the blob in memory until
 * it is revoked.</p>
 *
 * <p><b>A book with no cover is not a failure.</b> Most books have none, and
 * the drawn spine is what they have always shown. A cover that fails to load -
 * removed between the listing and the fetch, or a store that is down - falls
 * back to exactly the same thing rather than leaving a broken image.</p>
 */
export function BookCover({ book }: { book: BookSummary }) {
  const [source, setSource] = useState<string | null>(null);

  const coverUrl = book.hasCover ? book.coverUrl : null;

  useEffect(() => {
    if (coverUrl === null) {
      return;
    }

    let live = true;
    let created: string | null = null;

    apiObjectUrl(coverUrl)
      .then((url) => {
        if (live) {
          created = url;
          setSource(url);
        } else {
          // Arrived after unmount: release it rather than leak it.
          URL.revokeObjectURL(url);
        }
      })
      .catch(() => {
        // The drawn spine below is the fallback, and it needs no error state.
      });

    return () => {
      live = false;
      if (created !== null) {
        URL.revokeObjectURL(created);
      }
    };
  }, [coverUrl]);

  if (source !== null) {
    return (
      <img
        className="sl-book__cover-image"
        src={source}
        // The title is right beside this, so repeating it would make a screen
        // reader say the book twice. The image is decoration for a name that
        // is already there.
        alt=""
        loading="lazy"
        decoding="async"
        onError={() => setSource(null)}
      />
    );
  }

  const initial = book.title.trim().charAt(0).toUpperCase();

  return (
    <div className="sl-book__spine">
      <span className="sl-book__initial">{initial}</span>
      <span className="sl-book__spine-rules">
        <span />
        <span />
        <span />
      </span>
    </div>
  );
}
