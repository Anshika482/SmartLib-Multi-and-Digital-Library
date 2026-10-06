import { useState } from 'react';
import { Link } from 'react-router-dom';
import { PageHeading } from '../PageHeading';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { DataState } from '@/components/ui/DataState';
import { Skeleton } from '@/components/ui/Skeleton';
import { useAsync } from '@/hooks/useAsync';
import { ApiError } from '@/services/apiClient';
import { borrowRequestService } from '@/services/borrowRequestService';
import {
  isRequestActive,
  requestStatusLabel,
  requestStatusTone,
  type BorrowRequest,
  type Page,
} from '@/types/api';
import './RequestsPage.css';

/**
 * What a member has asked for, and how each request is getting on.
 *
 * <p>The list is the caller's own by construction: {@code /mine} takes no id,
 * so there is nothing here that could be pointed at somebody else. Withdrawing
 * is offered only while a request is still live - the server refuses the rest,
 * and hiding the button keeps somebody from finding that out the hard way.</p>
 *
 * <p><b>Nothing is guessed after an action.</b> Cancelling reloads the list
 * rather than editing the row in place: the server decides what a request is
 * now, and a screen that assumed would be wrong the moment two tabs disagree.</p>
 */
export function MyRequestsPage() {
  const requests = useAsync<Page<BorrowRequest>>(
    (signal) => borrowRequestService.mine({ size: 50 }, signal),
    [],
  );

  const [working, setWorking] = useState<number | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  async function cancel(request: BorrowRequest) {
    setWorking(request.id);
    setFailure(null);

    try {
      await borrowRequestService.cancel(request.id);
      requests.reload();
    } catch (error) {
      // The API's own words: it knows whether this was already decided, and
      // says so more usefully than a generic message could.
      setFailure(
        error instanceof ApiError ? error.message : 'That could not be withdrawn. Please try again.',
      );
    } finally {
      setWorking(null);
    }
  }

  return (
    <>
      <PageHeading title="My requests" lead="Books you have asked for." />

      {failure !== null && (
        <p className="sl-panel sl-requests__failure" role="alert">
          {failure}
        </p>
      )}

      <DataState
        data={requests.data?.content ?? null}
        loading={requests.loading}
        error={requests.error}
        onRetry={requests.reload}
        label="Your requests"
        emptyTitle="You have not asked for anything yet"
        emptyDetail="Find a book in the catalogue and ask for it; your library will decide."
        skeleton={
          <ul className="sl-requests">
            {[0, 1].map((slot) => (
              <li className="sl-panel sl-requests__row" key={slot}>
                <Skeleton height="1.1rem" width="55%" />
                <Skeleton height="0.9rem" width="30%" />
              </li>
            ))}
          </ul>
        }
      >
        {(items) => (
          <ul className="sl-requests">
            {items.map((request) => (
              <li className="sl-panel sl-requests__row" key={request.id}>
                <div className="sl-requests__main">
                  <Link className="sl-requests__title" to={`/app/catalogue/${request.bookId}`}>
                    {request.bookTitle}
                  </Link>
                  <p className="sl-requests__author">{request.bookAuthor}</p>
                </div>

                <div className="sl-requests__aside">
                  <Badge tone={requestStatusTone(request.status)}>
                    {requestStatusLabel(request.status)}
                  </Badge>

                  {request.status === 'APPROVED' && (
                    <p className="sl-requests__note">Collect it at the desk.</p>
                  )}

                  {isRequestActive(request.status) && (
                    <Button
                      variant="ghost"
                      busy={working === request.id}
                      disabled={working !== null}
                      onClick={() => void cancel(request)}
                    >
                      Withdraw
                    </Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </DataState>
    </>
  );
}
