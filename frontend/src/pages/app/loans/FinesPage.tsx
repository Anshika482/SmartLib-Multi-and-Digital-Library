import { PageHeading } from '../PageHeading';
import { LoanRow } from './LoanRow';
import { PayFineButton } from './PayFineButton';
import { DataState } from '@/components/ui/DataState';
import { Skeleton } from '@/components/ui/Skeleton';
import { useAuth } from '@/auth/useAuth';
import { useAsync } from '@/hooks/useAsync';
import { loanService } from '@/services/loanService';
import { isStaff, type Page, type Transaction } from '@/types/api';
import './LoanRow.css';

/**
 * What is owed.
 *
 * <p>One screen for both sides, because it is one endpoint: a member sees their
 * own fines and staff see their library's, and the server decides which from
 * the token rather than from anything this page sends. There is no id in the
 * request, so there is nothing a member could point elsewhere.</p>
 *
 * <p><b>These are the fines that can be paid.</b> A book still out is running up
 * a fine that is not settled until it comes back, so it is not here - staff see
 * those on the overdue list below, and a member sees them on their own loans.
 * The distinction is the server's, and this page reflects it rather than
 * reinterpreting it.</p>
 *
 * <p>Paying is Step 8. Nothing here takes money or claims to.</p>
 */
export function FinesPage() {
  const { user } = useAuth();
  const staff = user !== null && isStaff(user.role);

  const fines = useAsync<Page<Transaction>>(
    (signal) => loanService.fines({ size: 50 }, signal),
    [],
  );

  // Open loans running late. Only staff may ask for this list; a member's own
  // are already on their loans page.
  const overdue = useAsync<Page<Transaction>>(
    (signal) => loanService.byStatus('OVERDUE', { size: 50 }, signal),
    [],
    staff,
  );

  const rows = fines.data?.content ?? null;

  // Added up from what the server sent, each amount already decided by it.
  const owed = (rows ?? []).reduce((total, loan) => total + (loan.fineAmount ?? 0), 0);

  return (
    <>
      <PageHeading
        title={staff ? 'Fines owed' : 'Fines and payments'}
        lead={
          staff ? 'What is outstanding across your members.' : 'Anything owed, and how to settle it.'
        }
      />

      {rows !== null && rows.length > 0 && (
        <div className="sl-panel sl-loans__summary">
          <span className="sl-loans__summary-amount">{owed.toFixed(2)}</span>
          <p className="sl-loans__summary-note">
            {staff
              ? `outstanding across ${rows.length} ${rows.length === 1 ? 'loan' : 'loans'}`
              : 'owed. Settle it at the desk.'}
          </p>
        </div>
      )}

      <DataState
        data={rows}
        loading={fines.loading}
        error={fines.error}
        onRetry={fines.reload}
        label={staff ? 'Fines outstanding in your library' : 'Your fines'}
        emptyTitle={staff ? 'Nothing outstanding' : 'You owe nothing'}
        emptyDetail={
          staff
            ? 'No returned loan in your library has a fine left to settle.'
            : 'Books returned on time carry no fine.'
        }
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
              <LoanRow
                loan={loan}
                key={loan.id}
                showBorrower={staff}
                action={
                  // Offered to the member who owes it, on a fine that is
                  // actually payable. Staff settle fines at the desk through
                  // their own endpoint, and a book still out is still running
                  // up a fine that cannot be settled yet.
                  !staff && loan.status === 'RETURNED' && loan.finePaymentStatus === 'UNPAID' ? (
                    <PayFineButton
                      loan={loan}
                      payerName={user?.username ?? ''}
                      onPaid={fines.reload}
                    />
                  ) : undefined
                }
              />
            ))}
          </ul>
        )}
      </DataState>

      {staff && (
        <section className="sl-loans__section">
          <h2 className="sl-loans__section-title">Still out and running late</h2>

          <DataState
            data={overdue.data?.content ?? null}
            loading={overdue.loading}
            error={overdue.error}
            onRetry={overdue.reload}
            label="Overdue loans in your library"
            emptyTitle="Nothing is overdue"
            emptyDetail="Every book that is out is still within its due date."
            skeleton={
              <ul className="sl-loans">
                <li className="sl-panel sl-loan">
                  <Skeleton height="1.1rem" width="40%" />
                </li>
              </ul>
            }
          >
            {(items) => (
              <ul className="sl-loans">
                {items.map((loan) => (
                  <LoanRow loan={loan} key={loan.id} showBorrower />
                ))}
              </ul>
            )}
          </DataState>
        </section>
      )}
    </>
  );
}
