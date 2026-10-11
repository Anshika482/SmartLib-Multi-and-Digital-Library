import { beforeEach, describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { RequireAuth } from '@/app/guards';
import LoginPage from '@/features/auth/LoginPage';
import { useSession } from '@/stores/session';
import type { Me } from '@/api/types';
import { server } from './server';
import { renderRoutes } from './utils';

const member: Me = { id: 1, email: 'a@example.com', fullName: 'Asha Verma', phone: null, role: 'MEMBER', emailVerified: true, library: null, approvalStatus: null, createdAt: '2026-01-01T00:00:00Z' };

describe('Route guards', () => {
  beforeEach(() => useSession.setState({ status: 'anonymous', accessToken: null, user: null }));

  it('redirects anonymous users to login, keeping where they were going', async () => {
    const { router } = renderRoutes([
      { path: '/app/member', element: <RequireAuth roles={['MEMBER']}><p>member area</p></RequireAuth> },
      { path: '/login', element: <p>login page</p> },
    ], '/app/member?tab=fines');
    expect(await screen.findByText('login page')).toBeInTheDocument();
    expect(router.state.location.search).toBe(`?next=${encodeURIComponent('/app/member?tab=fines')}`);
  });

  it('shows a forbidden page for the wrong role (backend still enforces access)', async () => {
    useSession.setState({ status: 'authenticated', accessToken: 't', user: member });
    renderRoutes([{ path: '/app/admin', element: <RequireAuth roles={['ADMIN']}><p>admin area</p></RequireAuth> }], '/app/admin');
    expect(await screen.findByText("You don't have access to this area")).toBeInTheDocument();
    expect(screen.queryByText('admin area')).not.toBeInTheDocument();
  });
});

describe('Login states', () => {
  beforeEach(() => useSession.setState({ status: 'anonymous', accessToken: null, user: null }));

  it('unverified users get the verification flow instead of a session', async () => {
    server.use(http.post('/api/auth/login', () => HttpResponse.json({ status: 403, code: 'EMAIL_NOT_VERIFIED', detail: 'Your email is not verified yet.', email: 'new@example.com' }, { status: 403 })));
    const user = userEvent.setup();
    renderRoutes([{ path: '/login', element: <LoginPage /> }], '/login');
    await user.type(screen.getByLabelText(/Email/), 'new@example.com');
    await user.type(screen.getByLabelText(/^Password/), 'Secret123');
    await user.click(screen.getByRole('button', { name: 'Log in' }));
    expect(await screen.findByText('Your email is not verified')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verify email' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Resend verification' })).toBeInTheDocument();
    expect(useSession.getState().accessToken).toBeNull();
  });

  it('pending librarians see their approval status', async () => {
    server.use(http.post('/api/auth/login', () => HttpResponse.json({ status: 403, code: 'LIBRARIAN_APPROVAL_PENDING', detail: 'Waiting', libraryName: 'Central Library' }, { status: 403 })));
    const user = userEvent.setup();
    renderRoutes([{ path: '/login', element: <LoginPage /> }], '/login');
    await user.type(screen.getByLabelText(/Email/), 'lib@example.com');
    await user.type(screen.getByLabelText(/^Password/), 'Secret123');
    await user.click(screen.getByRole('button', { name: 'Log in' }));
    expect(await screen.findByText('Waiting for approval')).toBeInTheDocument();
    expect(screen.getByText('Central Library')).toBeInTheDocument();
    expect(screen.getByText('Administrator review')).toBeInTheDocument();
  });

  it('demo account buttons fill in the login form (only when the server enables them)', async () => {
    server.use(http.get('/api/public/demo-accounts', () => HttpResponse.json({ enabled: true, password: 'Demo-Pass-2026',
      accounts: [{ role: 'ADMIN', label: 'Admin', email: 'admin.central@smartlib.demo', description: 'Central Library: staff, members and settings' }] })));
    const user = userEvent.setup();
    renderRoutes([{ path: '/login', element: <LoginPage /> }], '/login');
    expect(await screen.findByText('Use a demo account')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Admin/ }));
    expect(screen.getByLabelText(/Email/)).toHaveValue('admin.central@smartlib.demo');
    expect(screen.getByLabelText(/^Password/)).toHaveValue('Demo-Pass-2026');
    expect(screen.getByRole('status')).toHaveTextContent('Click Log in');
  });

  it('a successful login stores the session in memory and routes by role', async () => {
    server.use(http.post('/api/auth/login', () => HttpResponse.json({ accessToken: 'jwt', expiresInSeconds: 900, user: member })));
    const user = userEvent.setup();
    const { router } = renderRoutes([{ path: '/login', element: <LoginPage /> }, { path: '/app/member', element: <p>member home</p> }], '/login');
    await user.type(screen.getByLabelText(/Email/), 'a@example.com');
    await user.type(screen.getByLabelText(/^Password/), 'Secret123');
    await user.click(screen.getByRole('button', { name: 'Log in' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/app/member'));
    expect(useSession.getState().accessToken).toBe('jwt');
    expect(localStorage.length).toBe(0);
  });
});
