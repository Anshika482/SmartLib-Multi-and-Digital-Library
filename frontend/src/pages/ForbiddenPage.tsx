import { Link } from 'react-router-dom';
import { useAuth } from '@/auth/useAuth';
import { roleLabel } from '@/types/api';
import { LinkButton } from '@/components/ui/AnchorButton';
import './ForbiddenPage.css';

/**
 * A page this account may not open.
 *
 * <p>Shown instead of a silent redirect, which leaves somebody wondering
 * whether they mistyped or the application is broken. It names the role they
 * are signed in as, because the usual cause is being signed in as the wrong
 * one.</p>
 *
 * <p><b>This is the interface being polite, not the thing stopping them.</b>
 * The route guard keeps them off the screen and the API refuses the requests it
 * would make; reaching this page means both of those are still in force.</p>
 */
export function ForbiddenPage() {
  const { user } = useAuth();

  return (
    <div className="sl-forbidden">
      <p className="sl-forbidden__code">403</p>
      <h1 className="sl-forbidden__title">That page is not yours to open</h1>
      <p className="sl-muted">
        {user === null
          ? 'Sign in with an account that has access to it.'
          : `You are signed in as ${user.username}, a ${roleLabel(user.role).toLowerCase()}. That page belongs to a different role.`}
      </p>

      <div className="sl-forbidden__actions">
        <LinkButton to="/app">Back to your library</LinkButton>
        <LinkButton to="/" variant="ghost">
          Public site
        </LinkButton>
      </div>

      <p className="sl-forbidden__note">
        If you believe you should have access, ask an administrator of your library.{' '}
        <Link to="/app/profile">See your account</Link>.
      </p>
    </div>
  );
}
