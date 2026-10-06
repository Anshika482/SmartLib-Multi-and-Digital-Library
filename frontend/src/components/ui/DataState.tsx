import type { ReactNode } from 'react';
import { Button } from './Button';
import { BookshelfIcon, WarningIcon } from './icons';
import './DataState.css';

interface DataStateProps<T> {
  /** Whatever came back, or null while it has not. */
  data: T[] | null;
  loading: boolean;
  error: string | null;
  /** Shown in place of the content while loading. */
  skeleton: ReactNode;
  /** What to say when the call worked and there is simply nothing yet. */
  emptyTitle: string;
  emptyDetail: string;
  onRetry: () => void;
  /** A label for the region, announced when its contents change. */
  label: string;
  children: (items: T[]) => ReactNode;
}

/**
 * The four things a list can be, in one place.
 *
 * <p>Loading, failed, empty, or loaded - and every section on the page goes
 * through here so none of them can quietly forget one. Empty is deliberately
 * not an error: a library that has not added a digital resource yet is working
 * exactly as intended, and saying "nothing went wrong, there is just nothing
 * here" is the honest thing to show.</p>
 *
 * <p>The region is a polite live region, so someone using a screen reader is
 * told when the content arrives instead of being left on "loading".</p>
 */
export function DataState<T>({
  data,
  loading,
  error,
  skeleton,
  emptyTitle,
  emptyDetail,
  onRetry,
  label,
  children,
}: DataStateProps<T>) {
  return (
    <div aria-busy={loading} aria-live="polite" aria-label={label} role="region">
      {loading && data === null && skeleton}

      {!loading && error !== null && (
        <div className="sl-state sl-state--error sl-panel">
          <span className="sl-state__icon">
            <WarningIcon />
          </span>
          <p className="sl-state__title">This didn&rsquo;t load</p>
          <p className="sl-state__detail">{error}</p>
          <Button variant="ghost" onClick={onRetry}>
            Try again
          </Button>
        </div>
      )}

      {!loading && error === null && data !== null && data.length === 0 && (
        <div className="sl-state sl-panel">
          <span className="sl-state__icon">
            <BookshelfIcon />
          </span>
          <p className="sl-state__title">{emptyTitle}</p>
          <p className="sl-state__detail">{emptyDetail}</p>
        </div>
      )}

      {error === null && data !== null && data.length > 0 && children(data)}
    </div>
  );
}
