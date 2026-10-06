import { Navigate, Outlet, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import type { Role } from '@/types/api';
import { useAuth } from './useAuth';
import { FullPageLoader } from '@/components/ui/FullPageLoader';
import { ForbiddenPage } from '@/pages/ForbiddenPage';

interface ProtectedRouteProps {
  /** When given, the route also requires one of these roles. */
  allow?: Role[];

  /**
   * What to render when the caller passes.
   *
   * <p>Omitted for a layout route, which renders its {@code Outlet} instead.
   * Given for a single screen, which lets one route carry its own role list
   * without needing a nested layout to hang it on.</p>
   */
  children?: ReactNode;
}

/**
 * A gate in front of a group of routes.
 *
 * <p><b>This hides pages; it does not secure them.</b> Every rule here exists
 * again in the API, which is what actually refuses a request - a person who
 * edits their way past this reaches endpoints that check the same things and
 * answer 401 or 403. What it buys is that nobody is shown a page they cannot
 * use, or a form whose submit was always going to be refused.</p>
 *
 * <p>While a stored session is being checked, neither the page nor the sign-in
 * screen is shown: redirecting first would bounce a signed-in person to the
 * sign-in page on every reload.</p>
 */
export function ProtectedRoute({ allow, children }: ProtectedRouteProps) {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) {
    return <FullPageLoader label="Opening your library" />;
  }

  if (user === null) {
    // Where they were going, so signing in can carry them there.
    return <Navigate to="/sign-in" replace state={{ from: location }} />;
  }

  if (allow !== undefined && !allow.includes(user.role)) {
    // Shown, not redirected. A silent bounce to the home page leaves somebody
    // wondering whether they mistyped or the application is broken; this says
    // which role they are signed in as, which is usually the answer.
    //
    // It is still only the interface being polite. The screen is not rendered
    // and every request it would make is refused by the API regardless.
    return <ForbiddenPage />;
  }

  return children === undefined ? <Outlet /> : <>{children}</>;
}
