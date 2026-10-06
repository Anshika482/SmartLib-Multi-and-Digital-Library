import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Badge } from '@/components/ui/Badge';
import { loanStatusLabel, overdueLabel, type Transaction } from '@/types/api';
import './LoanRow.css';

/**
 * One loan, as a row.
 *
 * <p><b>Every figure here came off the response.</b> Whether the loan is
 * overdue, how many days late it is and what it owes are all decided by the
 * server's one overdue policy. Nothing on this page subtracts a date or
 * multiplies a rate, because the browser's idea of today is not the server's
 * and a second implementation of the rule would drift from the first.</p>
 */
export function LoanRow({
  loan,
  showBorrower,
  action,
}: {
  loan: Transaction;
  showBorrower?: boolean;
  /** What may be done about this loan, if anything. Passed in by the screen. */
  action?: ReactNode;
}) {
  const overdue = loan.status === 'OVERDUE';
  const owes = loan.fineAmount !== null && loan.fineAmount > 0;

  return (
    <li className="sl-panel sl-loan">
      <div className="sl-loan__main">
        <Link className="sl-loan__title" to={`/app/catalogue/${loan.bookId}`}>
          {loan.bookTitle ?? 'This book'}
        </Link>
        {loan.bookAuthor !== null && <p className="sl-loan__author">{loan.bookAuthor}</p>}

        <dl className="sl-loan__dates">
          <div>
            <dt>Due</dt>
            <dd>{loan.dueDate}</dd>
          </div>
          {loan.returnDate !== null && (
            <div>
              <dt>Returned</dt>
              <dd>{loan.returnDate}</dd>
            </div>
          )}
          {showBorrower === true && (
            <div>
              <dt>Borrower</dt>
              {/* The account id, which is what staff can act on. No name or
                  email: the loan endpoint does not carry them, and it should
                  not start to. */}
              <dd>#{loan.userId}</dd>
            </div>
          )}
        </dl>
      </div>

      <div className="sl-loan__aside">
        <Badge tone={overdue ? 'out' : loan.status === 'ISSUED' ? 'available' : 'accent'}>
          {loanStatusLabel(loan.status)}
        </Badge>

        {loan.daysOverdue > 0 && (
          <span className="sl-loan__late">{overdueLabel(loan.daysOverdue)}</span>
        )}

        {owes && (
          <span className="sl-loan__fine">
            {loan.fineAmount?.toFixed(2)}
            <span className="sl-loan__fine-state">
              {loan.finePaymentStatus === 'PAID'
                ? 'paid'
                : loan.status === 'RETURNED'
                  ? 'to pay'
                  : 'so far'}
            </span>
          </span>
        )}

        {action}
      </div>
    </li>
  );
}
