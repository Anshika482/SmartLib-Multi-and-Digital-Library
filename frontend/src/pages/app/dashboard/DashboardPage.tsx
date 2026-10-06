import { PageHeading } from '../PageHeading';
import { StatCard } from './StatCard';
import { DataState } from '@/components/ui/DataState';
import { Skeleton } from '@/components/ui/Skeleton';
import { useAuth } from '@/auth/useAuth';
import { useAsync } from '@/hooks/useAsync';
import { dashboardService } from '@/services/dashboardService';
import { activityLabel, type Dashboard } from '@/types/api';
import './DashboardPage.css';

/**
 * What an account sees when it opens the application.
 *
 * <p>One request, and the server decides what comes back from the caller's own
 * account. This page renders whichever sections it was given: a member's
 * response simply has no circulation figures in it, so there is no role check
 * here deciding what to draw - the shape of the data already did.</p>
 *
 * <p><b>Every figure is real.</b> Each is a count or a sum the database
 * answered. A library with nothing in it shows zeros, because that is what is
 * true, and nothing on this page is filled in to look busier.</p>
 */
export function DashboardPage() {
  const { user } = useAuth();

  const dashboard = useAsync<Dashboard>((signal) => dashboardService.load(signal), []);

  const data = dashboard.data;

  return (
    <>
      <PageHeading
        title={user === null ? 'Overview' : `Good to see you, ${user.username}`}
        lead={data?.libraryName ?? undefined}
      />

      <DataState
        data={data === null ? null : [data]}
        loading={dashboard.loading}
        error={dashboard.error}
        onRetry={dashboard.reload}
        label="Your dashboard"
        emptyTitle="Nothing to show yet"
        emptyDetail="Your dashboard will fill in as your library is used."
        skeleton={
          <ul className="sl-stats">
            {[0, 1, 2, 3].map((slot) => (
              <li className="sl-panel sl-stat" key={slot}>
                <Skeleton height="0.7rem" width="55%" />
                <Skeleton height="1.8rem" width="35%" />
              </li>
            ))}
          </ul>
        }
      >
        {([summary]) => <DashboardSections summary={summary} />}
      </DataState>
    </>
  );
}

/**
 * The panels themselves.
 *
 * <p>Each section renders only if the server sent it. That is the same rule the
 * navigation follows and the same rule the API enforces - three statements of
 * one thing, of which only the last is a control.</p>
 */
function DashboardSections({ summary }: { summary: Dashboard }) {
  return (
    <>
      {summary.member !== null && (
        <Section title="Your borrowing">
          <StatCard
            label="Current loans"
            value={summary.member.currentLoans}
            to="/app/loans"
            note="Books you have out"
          />
          <StatCard
            label="Overdue"
            value={summary.member.overdueLoans}
            to="/app/loans"
            attention={summary.member.overdueLoans > 0}
            note={summary.member.overdueLoans > 0 ? 'Past the due date' : 'Nothing late'}
          />
          <StatCard
            label="Unpaid fines"
            value={summary.member.unpaidFines}
            to="/app/fines"
            attention={summary.member.unpaidFines > 0}
          />
          <StatCard
            label="Amount owed"
            value={summary.member.amountOwed.toFixed(2)}
            to="/app/fines"
            attention={summary.member.amountOwed > 0}
          />
        </Section>
      )}

      {summary.circulation !== null && (
        <Section title="Circulation">
          <StatCard
            label="Loans out"
            value={summary.circulation.activeLoans}
            to="/app/circulation"
          />
          <StatCard
            label="Overdue"
            value={summary.circulation.overdueLoans}
            to="/app/circulation"
            attention={summary.circulation.overdueLoans > 0}
          />
          <StatCard
            label="Unpaid fines"
            value={summary.circulation.unpaidFines}
            to="/app/fines-owed"
            attention={summary.circulation.unpaidFines > 0}
          />
          <StatCard
            label="Outstanding"
            value={summary.circulation.finesOutstanding.toFixed(2)}
            to="/app/fines-owed"
            attention={summary.circulation.finesOutstanding > 0}
          />
        </Section>
      )}

      {summary.catalogue !== null && (
        <Section title="Catalogue">
          <StatCard label="Titles" value={summary.catalogue.titles} to="/app/catalogue" />
          <StatCard label="Categories" value={summary.catalogue.categories} to="/app/catalogue" />
          <StatCard
            label="Online"
            value={summary.catalogue.digitalResources}
            to={summary.member !== null ? '/app/reading' : '/app/resources'}
            note="Readable online"
          />
        </Section>
      )}

      {summary.people !== null && (
        <Section title="People">
          <StatCard label="Members" value={summary.people.members} to="/app/members" />
          <StatCard label="Librarians" value={summary.people.librarians} to="/app/staff" />
          <StatCard label="Administrators" value={summary.people.administrators} to="/app/staff" />
          <StatCard
            label="Awaiting approval"
            value={summary.people.pendingRegistrations}
            to="/app/registrations"
            attention={summary.people.pendingRegistrations > 0}
            note={summary.people.pendingRegistrations > 0 ? 'Needs a decision' : 'Nothing waiting'}
          />
        </Section>
      )}

      {summary.system !== null && (
        <Section title="System">
          <StatCard label="Libraries" value={summary.system.libraries} to="/app/libraries" />
          <StatCard label="Accounts" value={summary.system.accounts} />
          <StatCard
            label="Library applications"
            value={summary.system.pendingLibraryApplications}
            to="/app/registrations"
            attention={summary.system.pendingLibraryApplications > 0}
            note={
              summary.system.pendingLibraryApplications > 0
                ? 'Would open a new library'
                : 'Nothing waiting'
            }
          />
        </Section>
      )}

      {summary.recentActivity.length > 0 && (
        <section className="sl-dash__section">
          <h2 className="sl-dash__section-title">Recent activity</h2>
          <div className="sl-panel">
            <ul className="sl-activity">
              {summary.recentActivity.map((entry, index) => (
                <li className="sl-activity__row" key={`${entry.occurredAt}-${index}`}>
                  <span>{activityLabel(entry.action)}</span>
                  <span className="sl-activity__when">
                    {new Date(entry.occurredAt).toLocaleString()}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        </section>
      )}
    </>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="sl-dash__section">
      <h2 className="sl-dash__section-title">{title}</h2>
      <ul className="sl-stats">
        {/* Each card is a list item so the group reads as a list of figures. */}
        {Array.isArray(children)
          ? children.map((card, index) => <li key={index}>{card}</li>)
          : <li>{children}</li>}
      </ul>
    </section>
  );
}
