import { Route } from 'react-router-dom';
import type { ReactNode } from 'react';
import { ProtectedRoute } from '@/auth/ProtectedRoute';
import { AppShell } from '@/layouts/AppShell';
import { PlaceholderPage } from '@/pages/app/PlaceholderPage';
import { DashboardPage } from '@/pages/app/dashboard/DashboardPage';
import { BookDetailsPage } from '@/pages/app/catalogue/BookDetailsPage';
import { CataloguePage } from '@/pages/app/catalogue/CataloguePage';
import { FinesPage } from '@/pages/app/loans/FinesPage';
import { ReportsPage } from '@/pages/app/reports/ReportsPage';
import { ReadingPage } from '@/pages/app/reading/ReadingPage';
import { ResourceReaderPage } from '@/pages/app/reading/ResourceReaderPage';
import { MyLoansPage } from '@/pages/app/loans/MyLoansPage';
import { MyRequestsPage } from '@/pages/app/requests/MyRequestsPage';
import { StaffRequestsPage } from '@/pages/app/requests/StaffRequestsPage';
import type { Role } from '@/types/api';

const MEMBER: Role = 'ROLE_MEMBER';
const LIBRARIAN: Role = 'ROLE_LIBRARIAN';
const ADMIN: Role = 'ROLE_ADMIN';
const SUPER_ADMIN: Role = 'ROLE_SUPER_ADMIN';

const STAFF: Role[] = [LIBRARIAN, ADMIN, SUPER_ADMIN];
const EVERYONE: Role[] = [MEMBER, LIBRARIAN, ADMIN, SUPER_ADMIN];
const ADMINS: Role[] = [ADMIN, SUPER_ADMIN];

/**
 * One screen: its path, who may open it, and what it will do.
 *
 * <p>The {@code allow} list here and the roles in {@code navigation.ts} say the
 * same thing, and a test asserts they agree - a link nobody may follow, or a
 * route nobody is offered, is a mistake either way.</p>
 */
interface AppScreen {
  path: string;
  allow: Role[];
  title: string;
  lead: string;
  coming: string[];

  /**
   * The built screen, for the ones that exist.
   *
   * <p>Left out while a screen is still a placeholder, which is what
   * {@code title}, {@code lead} and {@code coming} describe. Filling this in is
   * how a step hands a real page over.</p>
   */
  element?: ReactNode;
}

/**
 * Every screen behind the gate.
 *
 * <p>The ones that are built carry an {@code element}; the rest are
 * placeholders, routed and reachable so the navigation is real rather than a
 * list of dead links, and each filled in by the step that owns it.</p>
 *
 * <p>Every path here is a sidebar entry, and a test requires the two lists to
 * agree. A page reached from a list rather than the sidebar - a book's own page,
 * say - is therefore routed below instead of added here.</p>
 */
export const APP_SCREENS: AppScreen[] = [
  {
    path: '/app',
    allow: EVERYONE,
    title: 'Overview',
    lead: 'Where your library stands today.',
    coming: [
      'See what matters to your role at a glance',
      'Jump straight to whatever needs attention',
    ],
    element: <DashboardPage />,
  },
  {
    path: '/app/catalogue',
    allow: EVERYONE,
    title: 'Catalogue',
    lead: 'Everything your library holds.',
    coming: [
      'Search by title, author or ISBN and filter by category',
      'See covers and what is on the shelf right now',
      'Open a book to read what is available online',
    ],
    element: <CataloguePage />,
  },
  {
    path: '/app/my-requests',
    allow: [MEMBER],
    title: 'My requests',
    lead: 'Books you have asked for.',
    coming: [],
    element: <MyRequestsPage />,
  },
  {
    path: '/app/loans',
    allow: [MEMBER],
    title: 'My loans',
    lead: 'What you have out, and when it is due.',
    coming: ['See your current loans and due dates', 'Look back over what you have borrowed'],
    element: <MyLoansPage />,
  },
  {
    path: '/app/fines',
    allow: [MEMBER],
    title: 'Fines and payments',
    lead: 'Anything owed, and how to settle it.',
    coming: ['See what is owed and why', 'Pay online, or see what was paid at the desk'],
    element: <FinesPage />,
  },
  {
    path: '/app/reading',
    allow: [MEMBER],
    title: 'Digital reading',
    lead: 'Chapters, e-books, recordings and links.',
    coming: ['Open what your library has published online', 'Pick up where you left off'],
    element: <ReadingPage />,
  },
  {
    path: '/app/circulation',
    allow: STAFF,
    title: 'Issue and return',
    lead: 'The desk, in one screen.',
    coming: ['Issue a copy to a member', 'Take one back and close the loan', 'See what is overdue'],
  },
  {
    path: '/app/requests',
    allow: STAFF,
    title: 'Requests',
    lead: 'What members have asked for.',
    coming: ['Review requests for a title', 'Approve or decline, with a reason'],
    element: <StaffRequestsPage />,
  },
  {
    path: '/app/fines-owed',
    allow: STAFF,
    title: 'Fines owed',
    lead: 'What is outstanding across your members.',
    coming: ['See every unpaid fine', 'Record a payment taken at the desk'],
    // The same screen as a member's, which is right: it is one endpoint, and
    // the server decides whether the caller sees their own fines or the
    // library's.
    element: <FinesPage />,
  },
  {
    path: '/app/books',
    allow: STAFF,
    title: 'Books',
    lead: 'Your catalogue, and the shape of it.',
    coming: ['Add, edit and withdraw titles', 'Set a cover image', 'Organise categories'],
  },
  {
    path: '/app/resources',
    allow: STAFF,
    title: 'Digital resources',
    lead: 'What your library offers to read online.',
    coming: [
      'Attach a PDF, e-book, video or link to a book',
      'Turn a resource off without deleting it',
    ],
  },
  {
    path: '/app/members',
    allow: STAFF,
    title: 'Members',
    lead: 'Who belongs to your library.',
    coming: ['Find a member and see their loans', 'Enrol somebody', 'Enable, disable or unlock an account'],
  },
  {
    path: '/app/staff',
    allow: ADMINS,
    title: 'Staff',
    lead: 'The people who run your library.',
    coming: ['See your librarians and administrators', 'Add a colleague', 'Change what somebody can do'],
  },
  {
    path: '/app/registrations',
    allow: ADMINS,
    title: 'Registrations',
    lead: 'Applications waiting on a decision.',
    coming: ['Review who has applied and for what', 'Approve or decline an application'],
  },
  {
    path: '/app/reports',
    // Every member of staff, not only administrators: a librarian runs the desk
    // and the figures are about the desk. The server scopes what they see to
    // their own library, and a super administrator's to every library.
    allow: STAFF,
    title: 'Reports',
    lead: 'What your library actually did.',
    coming: ['Borrowing over time', 'What is most in demand', 'Fines raised and settled'],
    element: <ReportsPage />,
  },
  {
    path: '/app/libraries',
    allow: [SUPER_ADMIN],
    title: 'Libraries',
    lead: 'Every library on this deployment.',
    coming: ['See each library and its administrators', 'Approve an application to open a new one'],
  },
  {
    path: '/app/audit',
    allow: ADMINS,
    title: 'Audit log',
    lead: 'Who did what, and when.',
    coming: ['Read the trail for your library', 'Filter by action, person or date'],
  },
  {
    path: '/app/profile',
    allow: EVERYONE,
    title: 'Profile',
    lead: 'Your account.',
    coming: ['See your details and your library', 'Change your password'],
  },
];

/**
 * The protected half of the route table.
 *
 * <p>Returned as an array of elements rather than a component, because
 * {@code Routes} only accepts {@code Route} children - a wrapper component in
 * between would not be seen.</p>
 *
 * <p>Each screen is guarded by its own {@code ProtectedRoute}: the outer one
 * requires an account, the inner one requires a role. A caller who is signed in
 * but wrong for the page gets the 403 screen rather than a redirect.</p>
 */
export function appRoutes() {
  return (
    <Route element={<ProtectedRoute />}>
      <Route element={<AppShell />}>
        {APP_SCREENS.map((screen) => (
          <Route
            key={screen.path}
            path={screen.path}
            element={
              <ProtectedRoute allow={screen.allow}>
                {screen.element ?? (
                  <PlaceholderPage title={screen.title} lead={screen.lead} coming={screen.coming} />
                )}
              </ProtectedRoute>
            }
          />
        ))}

        {/* One book, reached from the catalogue rather than the sidebar - which
            is why it is here and not in APP_SCREENS. Open to everybody who may
            open the catalogue, and scoped by the API to their own library: a
            book belonging to another library answers 404. */}
        <Route
          path="/app/catalogue/:bookId"
          element={
            <ProtectedRoute allow={EVERYONE}>
              <BookDetailsPage />
            </ProtectedRoute>
          }
        />

        {/* One resource, opened. Reached from the reading list or from a book,
            never from the sidebar - which is why it is here rather than in
            APP_SCREENS. Whether it may be opened at all is the API's decision:
            a disabled resource and another library's are both 404. */}
        <Route
          path="/app/reading/:resourceId"
          element={
            <ProtectedRoute allow={EVERYONE}>
              <ResourceReaderPage />
            </ProtectedRoute>
          }
        />
      </Route>
    </Route>
  );
}
