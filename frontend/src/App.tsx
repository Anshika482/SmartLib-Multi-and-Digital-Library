import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider } from '@/auth/AuthProvider';
import { appRoutes } from '@/app/AppRoutes';
import { AppLayout } from '@/layouts/AppLayout';
import { SignInPage } from '@/pages/SignInPage';
import { RegisterPage } from '@/pages/RegisterPage';
import { HomePage } from '@/pages/HomePage';
import { NotFoundPage } from '@/pages/NotFoundPage';
import { ForbiddenPage } from '@/pages/ForbiddenPage';

/**
 * Every route in the application.
 *
 * <p>Three groups. The sign-in page, which needs no shell; the public shell,
 * which is the home page and nothing else; and the protected shell, which is
 * where every module of the application goes.</p>
 *
 * <p><b>The home page is deliberately outside the gate.</b> It is the front
 * door: somebody who has not signed in can read what SmartLib is and find the
 * way in. It shows no library data to them - every catalogue endpoint requires
 * authentication - so being public costs nothing and hides nothing.</p>
 *
 * <p><b>Everything else is added to the protected group.</b> A page dropped
 * into the public group by accident would be reachable signed-out, so the
 * protected group is the default home for new pages and the public one holds
 * exactly the route named in it.</p>
 */
export function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/sign-in" element={<SignInPage />} />
          <Route path="/register" element={<RegisterPage />} />

          {/* Public: the front door, and only the front door. */}
          <Route element={<AppLayout />}>
            <Route path="/" element={<HomePage />} />
          </Route>

          {/*
           * Protected: every screen of the application, inside its own shell.
           * The gate is unchanged - it still sends a signed-out visitor to
           * /sign-in and carries where they were going - and each screen adds
           * its own role check on top, which shows a 403 rather than a blank
           * page or a silent redirect.
           */}
          {appRoutes()}

          <Route path="/403" element={<ForbiddenPage />} />
          <Route path="/404" element={<NotFoundPage />} />
          <Route path="*" element={<Navigate to="/404" replace />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
