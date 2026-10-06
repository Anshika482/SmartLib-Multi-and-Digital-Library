import { Link, Outlet } from 'react-router-dom';
import { useAuth } from '@/auth/useAuth';
import { roleLabel } from '@/types/api';
import { Button } from '@/components/ui/Button';
import { LinkButton } from '@/components/ui/AnchorButton';
import { SiteFooter } from '@/components/layout/SiteFooter';
import { ChatWidget } from '@/components/chat/ChatWidget';
import './AppLayout.css';

/**
 * The frame around every signed-in page.
 *
 * <p>Deliberately thin for now: a header that says where you are and who you
 * are, a slot for the page, and the footer. Navigation arrives with the pages
 * it would point at - a sidebar of links to screens that do not exist yet
 * would be a promise the application cannot keep.</p>
 *
 * <p>The shell wraps the public home page as well as the protected ones, so
 * the header has two states: who is signed in with a way out, or the two ways
 * in - signing in, and registering.</p>
 *
 * <p><b>{@code main} is full width, not a centred container.</b> A page that
 * wants the usual measure wraps its own content in {@code .sl-shell}; a page
 * with a full-bleed band, like the home hero, simply does not. Doing it the
 * other way round forces the band to escape with {@code 100vw}, which counts
 * the scrollbar and puts the whole document into horizontal scroll.</p>
 */
export function AppLayout() {
  const { user, signOut } = useAuth();

  return (
    <div className="sl-app" id="top">
      <header className="sl-app__header">
        <div className="sl-shell sl-app__bar">
          <Link className="sl-app__brand" to="/">
            <span className="sl-app__mark" aria-hidden="true" />
            <span className="sl-app__wordmark">SMARTLIB</span>
          </Link>

          {user === null && (
            <nav className="sl-app__nav" aria-label="Explore SmartLib">
              <a href="#how-it-works">How it works</a>
              <a href="#catalogue">Catalogue</a>
              <a href="#assistant">Assistant</a>
            </nav>
          )}

          {user !== null && (
            <div className="sl-app__who">
              <div className="sl-app__name">{user.username}</div>
              <div className="sl-app__role">{roleLabel(user.role)}</div>
            </div>
          )}

          {user === null ? (
            <>
              <LinkButton to="/sign-in" variant="ghost">
                Sign In
              </LinkButton>
              <LinkButton to="/register">Register</LinkButton>
            </>
          ) : (
            <Button variant="ghost" onClick={() => void signOut()}>
              Sign out
            </Button>
          )}
        </div>
      </header>

      <main className="sl-app__main" id="main">
        <Outlet />
      </main>

      <SiteFooter />

      {/* Floats above the page, signed in or not. */}
      <ChatWidget />
    </div>
  );
}
