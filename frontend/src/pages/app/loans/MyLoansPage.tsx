import { PageHeading } from '../PageHeading';
import { LoanRow } from './LoanRow';
import { DataState } from '@/components/ui/DataState';
import { Skeleton } from '@/components/ui/Skeleton';
import { useAuth } from '@/auth/useAuth';
import { useAsync } from '@/hooks/useAsync';
import { loanService } from '@/services/loanService';
import { isLoanOpen, type Page, type Transaction } from '@/types/api';
import './LoanRow.css';

/**
 * What a member has out, and what they have had.
 *
 * <p>The id asked for is the caller's own, off their profile. Sending somebody
 * else's would be refused by the API, which compares the id against the account
 * the token names - so this is not a guard, only the right question to ask.</p>
 *
 * <p>Open loans come first because they are the ones that need attention; the
 * rest is history. That ordering is applied here over one page of results, not
 * by asking the server for two lists.</p>
 */
export function MyLoansPage() {
  const { user } = useAuth();

  const loans = useAsync<Page<Transaction>>(
    (signal) => loanService.forUser(user?.id ?? 0, { size: 50 }, signal),
    [user?.id],
    user !== null,
  );

  const rows = loans.data?.content ?? null;

  // Still out first, then by due date - the order a person would work through
  // them. Returned loans keep their own order below.
  const ordered =
    rows === null
      ? null
      : [...rows].sort((left, right) => {
          const leftOpen = isLoanOpen(left.status);
          const rightOpen = isLoanOpen(right.status);
          if (leftOpen !== rightOpen) {
            return leftOpen ? -1 : 1;
          }
          return left.dueDate.localeCompare(right.dueDate);
        });

  const out = ordered?.filter((loan) => isLoanOpen(loan.status)).length ?? 0;

  return (
    <>
      <PageHeading
        title="My loans"
        lead={
          loans.data === null
            ? 'What you have out, and when it is due.'
            : out === 0
              ? 'Nothing out at the moment.'
              : `${out} ${out === 1 ? 'book' : 'books'} out.`
        }
      />

      <DataState
        data={ordered}
        loading={loans.loading}
        error={loans.error}
        onRetry={loans.reload}
        label="Your loans"
        emptyTitle="You have not borrowed anything yet"
        emptyDetail="Books you borrow will appear here, with their due dates."
        skeleton={
          <ul className="sl-loans">
            {[0, 1].map((slot) => (
              <li className="sl-panel sl-loan" key={slot}>
                <Skeleton height="1.1rem" width="45%" />
                <Skeleton height="0.9rem" width="20%" />
              </li>
            ))}
          </ul>
        }
      >
        {(items) => (
          <ul className="sl-loans">
            {items.map((loan) => (
              <LoanRow loan={loan} key={loan.id} />
            ))}
          </ul>
        )}
      </DataState>
    </>
  );
}
