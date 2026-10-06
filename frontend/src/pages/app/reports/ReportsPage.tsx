import { useState } from 'react';
import { PageHeading } from '../PageHeading';
import { Button } from '@/components/ui/Button';
import { DataState } from '@/components/ui/DataState';
import { Skeleton } from '@/components/ui/Skeleton';
import { useAsync } from '@/hooks/useAsync';
import { reportService } from '@/services/reportService';
import { monthLabel, type Report } from '@/types/api';
import { reportToCsv } from './reportCsv';
import './ReportsPage.css';

/** The range a report opens on: the last ninety days, ending today. */
function defaultRange(): { from: string; to: string } {
  const today = new Date();
  const start = new Date(today);
  start.setDate(start.getDate() - 89);

  return { from: start.toISOString().slice(0, 10), to: today.toISOString().slice(0, 10) };
}

/**
 * What the library did, over a range of days.
 *
 * <p><b>Every number on this page was counted by the database.</b> Nothing here
 * adds anything up: the totals, the breakdowns and the monthly figures all
 * arrive as they will be shown. A total worked out in the browser from one page
 * of rows would be wrong the moment there were two pages, and these are the
 * library's accounts.</p>
 *
 * <p><b>The scope is the server's decision.</b> A librarian or administrator
 * gets their own library; a super administrator gets every library, and the
 * page says which it is showing rather than assuming. No request from here
 * names a library, because there is no parameter for one.</p>
 */
export function ReportsPage() {
  const initial = defaultRange();

  // What has been asked for, which is what the report describes...
  const [range, setRange] = useState(initial);
  // ...and what is in the two boxes, which may not be the same yet.
  const [draft, setDraft] = useState(initial);

  const report = useAsync<Report>(
    (signal) => reportService.load(range.from, range.to, signal),
    [range.from, range.to],
  );

  const data = report.data;
  const inverted = draft.to < draft.from;

  return (
    <>
      <PageHeading
        title="Reports"
        lead={
          data === null
            ? 'What your library actually did.'
            : data.systemWide
              ? 'Every library on this deployment.'
              : (data.libraryName ?? 'Your library')
        }
      />

      <form
        className="sl-panel sl-report__filters"
        onSubmit={(event) => {
          event.preventDefault();
          if (!inverted) {
            setRange(draft);
          }
        }}
      >
        <div className="sl-report__field">
          <label className="sl-report__label" htmlFor="report-from">
            From
          </label>
          <input
            className="sl-report__input"
            id="report-from"
            type="date"
            value={draft.from}
            onChange={(event) => setDraft({ ...draft, from: event.target.value })}
          />
        </div>

        <div className="sl-report__field">
          <label className="sl-report__label" htmlFor="report-to">
            To
          </label>
          <input
            className="sl-report__input"
            id="report-to"
            type="date"
            value={draft.to}
            onChange={(event) => setDraft({ ...draft, to: event.target.value })}
          />
        </div>

        <div className="sl-report__actions">
          <Button type="submit" disabled={inverted}>
            Show
          </Button>
          {data !== null && (
            <Button type="button" variant="ghost" onClick={() => download(data)}>
              Export CSV
            </Button>
          )}
        </div>

        {inverted && (
          // Said here rather than sent: the server refuses an inverted range,
          // but there is no reason to make somebody wait for that answer.
          <p className="sl-report__warning" role="alert">
            The end date is before the start date.
          </p>
        )}
      </form>

      <DataState
        data={data === null ? null : [data]}
        loading={report.loading}
        error={report.error}
        onRetry={report.reload}
        label="Report"
        emptyTitle="Nothing to report"
        emptyDetail="No figures came back for those dates."
        skeleton={
          <ul className="sl-report__stats">
            {[0, 1, 2, 3, 4, 5].map((slot) => (
              <li className="sl-panel sl-report__stat" key={slot}>
                <Skeleton height="0.7rem" width="60%" />
                <Skeleton height="1.6rem" width="40%" />
              </li>
            ))}
          </ul>
        }
      >
        {([shown]) => <ReportBody report={shown} />}
      </DataState>
    </>
  );
}

/**
 * Saves the report as a CSV.
 *
 * <p>Built in the browser from the report already on screen and handed to the
 * browser as a blob, so there is no endpoint to secure, no file on a server and
 * no dependency - the rows are the ones being displayed.</p>
 */
function download(report: Report) {
  const blob = new Blob([reportToCsv(report)], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);

  const link = document.createElement('a');
  link.href = url;
  link.download = `smartlib-report-${report.from}-to-${report.to}.csv`;
  document.body.appendChild(link);
  link.click();
  link.remove();

  // The blob stays in memory until it is revoked.
  URL.revokeObjectURL(url);
}

function ReportBody({ report }: { report: Report }) {
  const totals = report.totals;

  return (
    <>
      <section className="sl-report__section">
        <h2 className="sl-report__section-title">In this period</h2>
        <ul className="sl-report__stats">
          <Stat label="Issues" value={totals.issues} />
          <Stat label="Returns" value={totals.returns} />
          <Stat label="Fines raised" value={totals.finesRaised.toFixed(2)} />
          <Stat label="Fines paid" value={totals.finesPaid.toFixed(2)} />
          <Stat label="Payments taken" value={totals.paymentsTaken.toFixed(2)} />
        </ul>
      </section>

      <section className="sl-report__section">
        <h2 className="sl-report__section-title">As things stand</h2>
        <ul className="sl-report__stats">
          <Stat label="Titles held" value={totals.totalBooks} />
          <Stat label="Members" value={totals.totalMembers} />
          <Stat label="Out on loan" value={totals.activeLoans} />
          <Stat label="Overdue" value={totals.overdueLoans} attention={totals.overdueLoans > 0} />
          <Stat
            label="Still owed"
            value={totals.finesOutstanding.toFixed(2)}
            attention={totals.finesOutstanding > 0}
          />
        </ul>
      </section>

      <section className="sl-report__section">
        <h2 className="sl-report__section-title">Borrowed most</h2>
        {report.mostIssued.length === 0 ? (
          <p className="sl-panel sl-report__none">Nothing went out in this period.</p>
        ) : (
          <table className="sl-panel sl-report__table">
            <thead>
              <tr>
                <th scope="col">Title</th>
                <th scope="col">Author</th>
                <th scope="col">Issues</th>
              </tr>
            </thead>
            <tbody>
              {report.mostIssued.map((row) => (
                <tr key={`${row.title}-${row.author}`}>
                  <td>{row.title}</td>
                  <td>{row.author}</td>
                  <td className="sl-report__number">{row.issues}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="sl-report__section">
        <h2 className="sl-report__section-title">Shelves borrowed from</h2>
        {report.categories.length === 0 ? (
          <p className="sl-panel sl-report__none">Nothing went out in this period.</p>
        ) : (
          <table className="sl-panel sl-report__table">
            <thead>
              <tr>
                <th scope="col">Category</th>
                <th scope="col">Issues</th>
              </tr>
            </thead>
            <tbody>
              {report.categories.map((row) => (
                <tr key={row.category}>
                  <td>{row.category}</td>
                  <td className="sl-report__number">{row.issues}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="sl-report__section">
        <h2 className="sl-report__section-title">Month by month</h2>
        {report.circulation.length === 0 ? (
          <p className="sl-panel sl-report__none">No borrowing in this period.</p>
        ) : (
          <MonthlyBars report={report} />
        )}
      </section>
    </>
  );
}

/**
 * Circulation per month, as bars.
 *
 * <p>Drawn with two divs and a percentage width rather than a charting library:
 * it is a comparison of a dozen numbers, which is what a bar is for, and it
 * reads correctly without CSS as a table would.</p>
 */
function MonthlyBars({ report }: { report: Report }) {
  const busiest = Math.max(
    1,
    ...report.circulation.map((point) => Math.max(point.issues, point.returns)),
  );

  const overdueByMonth = new Map(
    report.overdueTrend.map((point) => [`${point.year}-${point.month}`, point.count]),
  );

  return (
    <table className="sl-panel sl-report__table">
      <thead>
        <tr>
          <th scope="col">Month</th>
          <th scope="col">Issues</th>
          <th scope="col">Returns</th>
          <th scope="col">Fell overdue</th>
        </tr>
      </thead>
      <tbody>
        {report.circulation.map((point) => (
          <tr key={`${point.year}-${point.month}`}>
            <th scope="row">{monthLabel(point.year, point.month)}</th>
            <td>
              <Bar value={point.issues} of={busiest} tone="issues" />
            </td>
            <td>
              <Bar value={point.returns} of={busiest} tone="returns" />
            </td>
            <td className="sl-report__number">
              {overdueByMonth.get(`${point.year}-${point.month}`) ?? 0}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Bar({ value, of, tone }: { value: number; of: number; tone: 'issues' | 'returns' }) {
  return (
    <span className="sl-report__bar">
      <span
        className={`sl-report__bar-fill sl-report__bar-fill--${tone}`}
        style={{ width: `${Math.round((value / of) * 100)}%` }}
      />
      {/* The number is text, not only a length: a bar nobody can read is a
          decoration. It is also what a screen reader announces. */}
      <span className="sl-report__bar-value">{value}</span>
    </span>
  );
}

function Stat({
  label,
  value,
  attention,
}: {
  label: string;
  value: number | string;
  attention?: boolean;
}) {
  return (
    <li className="sl-panel sl-report__stat">
      <span className="sl-report__stat-label">{label}</span>
      <span
        className={
          attention === true ? 'sl-report__stat-value sl-report__stat-value--attention' : 'sl-report__stat-value'
        }
      >
        {value}
      </span>
    </li>
  );
}
