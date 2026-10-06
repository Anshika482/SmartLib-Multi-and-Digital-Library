import { useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { AuthLayout } from '@/layouts/AuthLayout';
import { Button } from '@/components/ui/Button';
import { TextField } from '@/components/ui/TextField';
import { useAuth } from '@/auth/useAuth';
import { ApiError } from '@/services/apiClient';
import './SignInPage.css';

/**
 * Signing in.
 *
 * <p>The only page that talks to an unauthenticated endpoint. Whatever goes
 * wrong, the message shown is the one the API chose: it deliberately says the
 * same thing for a wrong username and a wrong password, and repeating it here
 * keeps that property rather than inventing a friendlier wording that would
 * leak which half was right.</p>
 */
export function SignInPage() {
  const { signIn, user, loading } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Where they were headed before the gate sent them here.
  const destination = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname ?? '/';

  // Somebody already signed in has no business on this form; the back button
  // after signing in is the usual way to land here.
  if (!loading && user !== null) {
    return <Navigate to={destination} replace />;
  }

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setBusy(true);

    try {
      await signIn(username.trim(), password);
      navigate(destination, { replace: true });
    } catch (failure) {
      setError(
        failure instanceof ApiError ? failure.message : 'Something went wrong signing you in. Please try again.',
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthLayout>
      <div className="sl-signin">
        <header className="sl-signin__head">
          <h2 className="sl-signin__title">Welcome back</h2>
          <p className="sl-muted">Sign in to your library account.</p>
        </header>

        <form className="sl-signin__form" onSubmit={onSubmit} noValidate>
          <TextField
            label="Username"
            name="username"
            autoComplete="username"
            autoFocus
            required
            value={username}
            onChange={(event) => setUsername(event.target.value)}
          />

          <TextField
            label="Password"
            name="password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />

          {error !== null && (
            <p className="sl-signin__error" role="alert">
              {error}
            </p>
          )}

          <Button type="submit" busy={busy} fullWidth>
            {busy ? 'Signing in...' : 'Sign in'}
          </Button>
        </form>

        <p className="sl-signin__foot">
          New to SmartLib? <Link to="/register">Create an account</Link>
        </p>
      </div>
    </AuthLayout>
  );
}
