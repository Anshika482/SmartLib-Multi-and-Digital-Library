import { useEffect } from 'react';
import { Outlet, ScrollRestoration } from 'react-router';
import { refreshSession } from '@/api/http';
import { useSession } from '@/stores/session';

/** Restores the session from the httpOnly refresh cookie once per page load (so refresh/deep links keep you signed in). */
export function AppRoot() {
  const status = useSession((s) => s.status);
  useEffect(() => {
    if (status === 'unknown') void refreshSession();
  }, [status]);
  return (
    <>
      <ScrollRestoration />
      <Outlet />
    </>
  );
}
