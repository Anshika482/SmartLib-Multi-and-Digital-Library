import { createBrowserRouter, Navigate, type RouteObject } from 'react-router';
import { AppRoot } from './AppRoot';
import { RequireAuth, AppIndexRedirect } from './guards';
import { PublicLayout } from '@/layouts/PublicLayout';
import { AuthLayout } from '@/layouts/AuthLayout';
import { DashboardLayout } from '@/layouts/DashboardLayout';
import { ForbiddenPage, FullPageLoader, NotFoundPage } from '@/components/states';
import HomePage from '@/features/home/HomePage';
import CataloguePage from '@/features/catalogue/CataloguePage';
import BookDetailsPage from '@/features/catalogue/BookDetailsPage';
import { memberNav, librarianNav, adminNav, superAdminNav } from './navigation';

const lazy = (loader: () => Promise<{ default: React.ComponentType }>) => async () => {
  const m = await loader();
  return { Component: m.default };
};
const lazyNamed = <K extends string>(loader: () => Promise<Record<K, React.ComponentType>>, name: K) => async () => {
  const m = await loader();
  return { Component: m[name] };
};

/** Staff routes are shared by LIBRARIAN and ADMIN; admin-only pages are added for /app/admin. */
const staffRoutes = (admin: boolean): RouteObject[] => [
  { index: true, lazy: lazy(() => import('@/features/staff/StaffOverview')) },
  { path: 'requests', lazy: lazy(() => import('@/features/staff/RequestsPage')) },
  { path: 'books', lazy: lazy(() => import('@/features/staff/BooksPage')) },
  { path: 'books/new', lazy: lazy(() => import('@/features/staff/AddBookPage')) },
  { path: 'books/:holdingId', lazy: lazy(() => import('@/features/staff/HoldingDetailPage')) },
  { path: 'borrowings', lazy: lazy(() => import('@/features/staff/BorrowingsPage')) },
  { path: 'returns', element: <Navigate to="../borrowings?filter=ACTIVE" relative="path" replace /> },
  { path: 'fines', lazy: lazy(() => import('@/features/staff/FinesPage')) },
  { path: 'resources', lazy: lazy(() => import('@/features/staff/StaffResourcesPage')) },
  { path: 'announcements', lazy: lazy(() => import('@/features/staff/AnnouncementsPage')) },
  { path: 'reports', lazy: lazy(() => import('@/features/staff/ReportsPage')) },
  { path: 'members', lazy: lazy(() => import('@/features/staff/MembersPage')) },
  { path: 'notifications', lazy: lazy(() => import('@/features/shared/NotificationsPage')) },
  { path: 'profile', lazy: lazy(() => import('@/features/shared/ProfilePage')) },
  ...(admin ? [
    { path: 'librarians', lazy: lazy(() => import('@/features/staff/LibrariansPage')) },
    { path: 'audit', lazy: lazy(() => import('@/features/staff/AuditPage')) },
    { path: 'settings', lazy: lazy(() => import('@/features/staff/SettingsPage')) },
  ] : []),
];

export const router = createBrowserRouter([
  {
    element: <AppRoot />,
    // Shown while a lazily loaded route's code downloads on a direct visit or refresh.
    hydrateFallbackElement: <FullPageLoader />,
    children: [
      {
        element: <PublicLayout />,
        children: [
          { index: true, element: <HomePage /> },
          { path: 'catalogue', element: <CataloguePage /> },
          { path: 'catalogue/:bookId', element: <BookDetailsPage /> },
          { path: 'libraries', lazy: lazy(() => import('@/features/libraries/LibrariesPage')) },
          { path: 'libraries/:slug', lazy: lazy(() => import('@/features/libraries/LibraryDetailsPage')) },
          { path: 'assistant', lazy: lazy(() => import('@/features/assistant/AssistantPage')) },
          { path: '403', element: <ForbiddenPage /> },
        ],
      },
      {
        element: <AuthLayout />,
        children: [
          // Lazy: keeps the form libraries (React Hook Form, Zod) out of the landing-page bundle.
          { path: 'login', lazy: lazy(() => import('@/features/auth/LoginPage')) },
          { path: 'register', lazy: lazy(() => import('@/features/auth/RegisterPage')) },
          { path: 'register/librarian', lazy: lazy(() => import('@/features/auth/RegisterLibrarianPage')) },
          { path: 'register/admin', lazy: lazy(() => import('@/features/auth/RegisterAdminPage')) },
          { path: 'verify-email', lazy: lazy(() => import('@/features/auth/VerifyEmailPage')) },
          { path: 'forgot-password', lazy: lazyNamed(() => import('@/features/auth/PasswordPages'), 'ForgotPasswordPage') },
          { path: 'reset-password', lazy: lazyNamed(() => import('@/features/auth/PasswordPages'), 'ResetPasswordPage') },
          { path: 'accept-invite', lazy: lazyNamed(() => import('@/features/auth/PasswordPages'), 'AcceptInvitePage') },
        ],
      },
      { path: 'app', element: <RequireAuth><AppIndexRedirect /></RequireAuth> },
      {
        path: 'app/member',
        element: <RequireAuth roles={['MEMBER']}><DashboardLayout nav={memberNav} /></RequireAuth>,
        children: [
          { index: true, lazy: lazy(() => import('@/features/member/MemberOverview')) },
          { path: 'requests', lazy: lazy(() => import('@/features/member/MyRequestsPage')) },
          { path: 'borrowings', lazy: lazy(() => import('@/features/member/MyBorrowingsPage')) },
          { path: 'fines', lazy: lazy(() => import('@/features/member/MyFinesPage')) },
          { path: 'payments', lazy: lazy(() => import('@/features/member/MyPaymentsPage')) },
          { path: 'resources', lazy: lazy(() => import('@/features/member/MyResourcesPage')) },
          { path: 'notifications', lazy: lazy(() => import('@/features/shared/NotificationsPage')) },
          { path: 'assistant', lazy: lazy(() => import('@/features/assistant/AssistantPage')) },
          { path: 'profile', lazy: lazy(() => import('@/features/shared/ProfilePage')) },
        ],
      },
      {
        path: 'app/librarian',
        element: <RequireAuth roles={['LIBRARIAN']}><DashboardLayout nav={librarianNav} /></RequireAuth>,
        children: staffRoutes(false),
      },
      {
        path: 'app/admin',
        element: <RequireAuth roles={['ADMIN']}><DashboardLayout nav={adminNav} /></RequireAuth>,
        children: staffRoutes(true),
      },
      {
        path: 'app/super-admin',
        element: <RequireAuth roles={['SUPER_ADMIN']}><DashboardLayout nav={superAdminNav} /></RequireAuth>,
        children: [
          { index: true, lazy: lazy(() => import('@/features/platform/PlatformOverview')) },
          { path: 'libraries', lazy: lazy(() => import('@/features/platform/PlatformLibrariesPage')) },
          { path: 'libraries/:id', lazy: lazy(() => import('@/features/platform/PlatformLibraryDetail')) },
          { path: 'users', lazy: lazy(() => import('@/features/platform/PlatformUsersPage')) },
          { path: 'applications', lazy: lazy(() => import('@/features/platform/PlatformApplicationsPage')) },
          { path: 'catalogue', lazy: lazy(() => import('@/features/platform/PlatformCataloguePage')) },
          { path: 'reports', lazy: lazy(() => import('@/features/platform/PlatformReportsPage')) },
          { path: 'audit', lazy: lazy(() => import('@/features/platform/PlatformAuditPage')) },
          { path: 'config', lazy: lazy(() => import('@/features/platform/PlatformConfigPage')) },
          { path: 'notifications', lazy: lazy(() => import('@/features/shared/NotificationsPage')) },
          { path: 'profile', lazy: lazy(() => import('@/features/shared/ProfilePage')) },
        ],
      },
      { path: '*', element: <PublicLayout />, children: [{ path: '*', element: <NotFoundPage /> }] },
    ],
  },
]);
