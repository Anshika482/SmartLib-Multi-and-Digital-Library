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
  requestStatusLabel,
  requestStatusTone,
  type BorrowRequest,
  type BorrowRequestStatus,
  type Page,
} from '@/types/api';
import './RequestsPage.css';

/** How long a loan runs by default. The member of staff can change it before issuing. */
const DEFAULT_LOAN_DAYS = 14;

/** The queues a member of staff works through, in the order the work happens. */
const TABS: { status: BorrowRequestStatus; label: string }[] = [
  { status: 'REQUESTED', label: 'Waiting' },
  { status: 'APPROVED', label: 'Ready to collect' },
  { status: 'FULFILLED', label: 'Collected' },
  { status: 'REJECTED', label: 'Declined' },
];

function inDays(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return date.toISOString().slice(0, 10);
}

/**
 * The desk's queue: what members have asked for, and what to do about it.
 *
 * <p><b>Approving is not issuing, and the screen says so.</b> A waiting request
 * is approved or declined; only an approved one offers to hand the copy over,
 * and that is a second, separate action with a due date on it. The two are
 * distinct states on the server, and collapsing them here would misrepresent
 * what the buttons do.</p>
 *
 * <p>Every action reloads rather than editing the row: two members of staff may
 * be looking at the same queue, and the server is the only one that knows which
 * of them got there first. When it refuses - because the request was already
 * decided, or the last copy has gone - its own message is shown, since it is
 * more useful than anything this screen could infer.</p>
 */
export function StaffRequestsPage() {
  const [tab, setTab] = useState<BorrowRequestStatus>('REQUESTED');
  const [working, setWorking] = useState<number | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [dueDate, setDueDate] = useState(inDays(DEFAULT_LOAN_DAYS));

  const requests = useAsync<Page<BorrowRequest>>(
    (signal) => borrowRequestService.queue(tab, { size: 50 }, signal),
    [tab],
  );

  // Shown when something on screen could actually be issued, rather than when a
  // particular tab is open. The buttons below follow each row's own status, and
  // a control that decides what those buttons send has to follow the same
  // thing - otherwise a row could offer to issue with no date field in sight.
  const issuable = (requests.data?.content ?? []).some((request) => request.status === 'APPROVED');

  async function act(request: BorrowRequest, action: 'approve' | 'reject' | 'issue') {
    setWorking(request.id);
    setFailure(null);

    try {
      if (action === 'approve') {
        await borrowRequestService.approve(request.id);
      } else if (action === 'reject') {
        await borrowRequestService.reject(request.id);
      } else {
        await borrowRequestService.issue(request.id, dueDate);
      }
      requests.reload();
    } catch (error) {
      setFailure(
        error instanceof ApiError ? error.message : 'That could not be done. Please try again.',
      );
    } finally {
      setWorking(null);
    }
  }

  return (
    <>
      <PageHeading title="Requests" lead="What members have asked for." />

      <div className="sl-tabs" role="tablist" aria-label="Request queues">
        {TABS.map((entry) => (
          <button
            className={`sl-tabs__tab${tab === entry.status ? ' sl-tabs__tab--on' : ''}`}
            key={entry.status}
            type="button"
            role="tab"
            aria-selected={tab === entry.status}
            onClick={() => {
              setTab(entry.status);
              setFailure(null);
            }}
          >
            {entry.label}
          </button>
        ))}
      </div>

      {failure !== null && (
        <p className="sl-panel sl-requests__failure" role="alert">
          {failure}
        </p>
      )}

      {issuable && (
        <div className="sl-panel sl-requests__due">
          <label className="sl-requests__due-label" htmlFor="request-due-date">
            Due date for anything issued now
          </label>
          <input
            className="sl-requests__due-input"
            id="request-due-date"
            type="date"
            value={dueDate}
            min={inDays(0)}
            onChange={(event) => setDueDate(event.target.value)}
          />
        </div>
      )}

      <DataState
        data={requests.data?.content ?? null}
        loading={requests.loading}
        error={requests.error}
        onRetry={requests.reload}
        label="Requests in this queue"
        emptyTitle="Nothing here"
        emptyDetail={
          tab === 'REQUESTED'
            ? 'No member is waiting on a decision right now.'
            : 'Nothing in your library is in this state.'
        }
        skeleton={
          <ul className="sl-requests">
            {[0, 1, 2].map((slot) => (
              <li className="sl-panel sl-requests__row" key={slot}>
                <Skeleton height="1.1rem" width="50%" />
                <Skeleton height="0.9rem" width="25%" />
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
                  <p className="sl-requests__author">
                    {request.bookAuthor}
                    {request.memberName !== null && (
                      <>
                        {' · asked for by '}
                        <strong>{request.memberName}</strong>
                      </>
                    )}
                  </p>
                </div>

                <div className="sl-requests__aside">
                  <Badge tone={requestStatusTone(request.status)}>
                    {requestStatusLabel(request.status)}
                  </Badge>

                  {request.status === 'REQUESTED' && (
                    <>
                      <Button
                        busy={working === request.id}
                        disabled={working !== null}
                        onClick={() => void act(request, 'approve')}
                      >
                        Approve
                      </Button>
                      <Button
                        variant="ghost"
                        disabled={working !== null}
                        onClick={() => void act(request, 'reject')}
                      >
                        Decline
                      </Button>
                    </>
                  )}

                  {request.status === 'APPROVED' && (
                    <>
                      {/* A second, separate action: approving agreed to it, this
                          hands the copy over and writes the loan. */}
                      <Button
                        busy={working === request.id}
                        disabled={working !== null}
                        onClick={() => void act(request, 'issue')}
                      >
                        Issue the copy
                      </Button>
                      <Button
                        variant="ghost"
                        disabled={working !== null}
                        onClick={() => void act(request, 'reject')}
                      >
                        Decline
                      </Button>
                    </>
                  )}

                  {request.transactionId !== null && (
                    <p className="sl-requests__note">Issued as loan #{request.transactionId}</p>
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
