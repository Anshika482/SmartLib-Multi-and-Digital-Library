import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { PageHeading } from '../PageHeading';
import { BookCover } from '@/components/catalogue/BookCover';
import { availabilityOf } from '@/components/catalogue/availability';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { DataState } from '@/components/ui/DataState';
import { Skeleton } from '@/components/ui/Skeleton';
import { useAuth } from '@/auth/useAuth';
import { useAsync } from '@/hooks/useAsync';
import { ApiError } from '@/services/apiClient';
import { catalogueService } from '@/services/catalogueService';
import { borrowRequestService } from '@/services/borrowRequestService';
import { digitalResourceService } from '@/services/digitalResourceService';
import { isStaff, resourceTypeLabel, type BookSummary, type DigitalResource, type Page } from '@/types/api';
import './BookDetailsPage.css';

/** Enough resources for any real book, and bounded like every other list. */
const RESOURCE_PAGE = 20;

/**
 * One book, in full.
 *
 * <p>Two requests, each already scoped by the backend: the book, which is a 404
 * if it belongs to another library, and what that book has online, which comes
 * back filtered to what this caller may open. <b>Neither list is narrowed
 * here.</b> A member is not sent every resource and shown some of them - the
 * server never puts a disabled one in the response, so there is nothing on this
 * page that a different render could reveal.</p>
 *
 * <p>Borrowing is deliberately absent. Issuing and returning are the next
 * step's work, and this page points at the screen that will do it rather than
 * offering a control that would fail.</p>
 */
export function BookDetailsPage() {
  const { bookId } = useParams<{ bookId: string }>();
  const { user } = useAuth();

  const id = Number(bookId);
  const valid = Number.isInteger(id) && id > 0;

  const book = useAsync<BookSummary>((signal) => catalogueService.get(id, signal), [id], valid);

  const resources = useAsync<Page<DigitalResource>>(
    (signal) => digitalResourceService.list({ bookId: id, size: RESOURCE_PAGE }, signal),
    [id],
    valid,
  );

  const staff = user !== null && isStaff(user.role);

  if (!valid) {
    return (
      <>
        <PageHeading title="Book not found" crumb="Book" />
        <div className="sl-panel sl-details__missing">
          <p>That is not a book reference this library uses.</p>
          <Link to="/app/catalogue">Back to the catalogue</Link>
        </div>
      </>
    );
  }

  return (
    <>
      <PageHeading
        title={book.data?.title ?? 'Book'}
        lead={book.data?.author ?? undefined}
        crumb={book.data?.title ?? 'Book'}
      />

      <DataState
        data={book.data === null ? null : [book.data]}
        loading={book.loading}
        error={book.error}
        onRetry={book.reload}
        label="Book details"
        emptyTitle="Nothing to show"
        emptyDetail="This book could not be read."
        skeleton={
          <div className="sl-details sl-panel">
            <Skeleton height="14rem" radius="var(--radius-md)" />
            <div className="sl-details__facts">
              <Skeleton height="1.2rem" width="70%" />
              <Skeleton height="0.9rem" width="40%" />
              <Skeleton height="0.9rem" width="55%" />
            </div>
          </div>
        }
      >
        {([found]) => <BookFacts book={found} staff={staff} />}
      </DataState>

      <section className="sl-details__section">
        <h2 className="sl-details__section-title">Available online</h2>

        <DataState
          data={resources.data?.content ?? null}
          loading={resources.loading}
          error={resources.error}
          onRetry={resources.reload}
          label="Digital resources for this book"
          emptyTitle="Nothing online for this book"
          emptyDetail={
            staff
              ? 'Attach a PDF, e-book, video or link from the digital resources screen.'
              : 'Your library has not published anything online for this title.'
          }
          skeleton={
            <ul className="sl-resources">
              {[0, 1].map((slot) => (
                <li className="sl-panel sl-resources__row" key={slot}>
                  <Skeleton height="1rem" width="60%" />
                  <Skeleton height="0.8rem" width="30%" />
                </li>
              ))}
            </ul>
          }
        >
          {(items) => (
            <ul className="sl-resources">
              {items.map((resource) => (
                <li className="sl-panel sl-resources__row" key={resource.id}>
                  <div className="sl-resources__head">
                    {/* Into the reader, not straight at the file: the reader
                        is where the type decides what to do and where every
                        outward link is made safe. */}
                    <Link className="sl-resources__title" to={`/app/reading/${resource.id}`}>
                      {resource.title}
                    </Link>
                    <Badge tone="accent" plain>
                      {resourceTypeLabel(resource.resourceType)}
                    </Badge>
                    {/* Staff are sent disabled resources as well, so they are
                        told which ones a member cannot see. A member is never
                        sent one, and so never sees this badge. */}
                    {!resource.enabled && <Badge tone="out">Hidden from members</Badge>}
                  </div>
                  {resource.description !== null && resource.description.length > 0 && (
                    <p className="sl-resources__detail">{resource.description}</p>
                  )}
                </li>
              ))}
            </ul>
          )}
        </DataState>
      </section>

      <Borrowing staff={staff} bookId={id} />
    </>
  );
}

/** The bibliographic facts, the cover, and what is on the shelf. */
function BookFacts({ book, staff }: { book: BookSummary; staff: boolean }) {
  const availability = availabilityOf(book);
  const onLoan = Math.max(0, book.totalCopies - book.availableCopies);

  return (
    <div className="sl-details sl-panel">
      <div className="sl-details__cover" aria-hidden="true">
        <BookCover book={book} />
      </div>

      <dl className="sl-details__facts">
        {/* The author is the lead under the title, so it is not repeated here. */}
        <div className="sl-details__fact">
          <dt>ISBN</dt>
          <dd className="sl-details__isbn">{book.isbn}</dd>
        </div>

        <div className="sl-details__fact">
          <dt>Category</dt>
          <dd>
            {book.categoryName !== null && book.categoryName.length > 0 ? (
              book.categoryName
            ) : (
              <span className="sl-muted">Not shelved</span>
            )}
          </dd>
        </div>

        <div className="sl-details__fact">
          <dt>Availability</dt>
          <dd>
            {/* No srLabel: the detail is beside it in plain text, and passing
                both would have a screen reader read the same sentence twice. */}
            <Badge tone={availability.tone}>{availability.label}</Badge>{' '}
            <span className="sl-muted">{availability.detail}</span>
          </dd>
        </div>

        {staff && (
          // The stock breakdown is an operational figure: how many copies exist
          // and how many are out. A member is shown what they can borrow.
          <div className="sl-details__fact">
            <dt>Stock</dt>
            <dd>
              {book.totalCopies} held, {book.availableCopies} on the shelf, {onLoan} on loan
            </dd>
          </div>
        )}
      </dl>
    </div>
  );
}

/**
 * Asking for the book, or - for staff - where the copies are handled.
 *
 * <p>A member asks here and collects at the desk: requesting is not borrowing,
 * and nothing on this page moves stock. Once asked, the button is replaced by
 * a link to their own list rather than left clickable, because the server
 * refuses a second live request for the same book and offering it again would
 * only produce an error.</p>
 *
 * <p>Staff do not queue for books, so they are sent to the desk instead.</p>
 */
function Borrowing({ staff, bookId }: { staff: boolean; bookId: number }) {
  const [asked, setAsked] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  async function ask() {
    setBusy(true);
    setFailure(null);

    try {
      await borrowRequestService.request(bookId);
      setAsked(true);
    } catch (error) {
      // The API's own message. It knows whether this member already has a live
      // request, or the library holds no copy at all, and says which.
      setFailure(
        error instanceof ApiError ? error.message : 'That could not be requested. Please try again.',
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="sl-details__section">
      <h2 className="sl-details__section-title">Borrowing</h2>

      <div className="sl-panel sl-details__cta">
        {staff ? (
          <>
            <p className="sl-details__cta-text">
              Issuing and returning copies is handled at the desk.
            </p>
            <Link className="sl-details__cta-link" to="/app/requests">
              Go to requests
            </Link>
          </>
        ) : asked ? (
          <>
            <p className="sl-details__cta-text">
              Asked for. Your library will decide, and you can collect it at the desk once it is
              approved.
            </p>
            <Link className="sl-details__cta-link" to="/app/my-requests">
              See my requests
            </Link>
          </>
        ) : (
          <>
            <p className="sl-details__cta-text">
              Ask for this book and collect it at the desk once staff have approved it.
            </p>
            <Button busy={busy} disabled={busy} onClick={() => void ask()}>
              Request this book
            </Button>
          </>
        )}
      </div>

      {failure !== null && (
        <p className="sl-panel sl-details__failure" role="alert">
          {failure}
        </p>
      )}
    </section>
  );
}
