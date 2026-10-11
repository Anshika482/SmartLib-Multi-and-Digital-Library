import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import type { Role } from '@/api/types';
import { roleHome, useSession } from '@/stores/session';
import { ForbiddenPage, FullPageLoader } from '@/components/states';

/** UX guard only — every API call is still authorised by the backend. */
export function RequireAuth({ roles, children }: { roles?: Role[]; children: ReactNode }) {
  const { status, user } = useSession();
  const location = useLocation();
  if (status === 'unknown') return <FullPageLoader label="Restoring your session…" />;
  if (status === 'anonymous' || !user) {
    return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />;
  }
  if (roles && !roles.includes(user.role)) return <ForbiddenPage />;
  return <>{children}</>;
}

export function AppIndexRedirect() {
  const user = useSession((s) => s.user);
  return <Navigate to={roleHome(user?.role)} replace />;
}

export function safeNext(next: string | null | undefined) {
  if (!next || !next.startsWith('/') || next.startsWith('//')) return null;
  return next;
}
