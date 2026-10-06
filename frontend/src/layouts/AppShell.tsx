import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '@/auth/useAuth';
import { roleLabel } from '@/types/api';
import { navigationFor, type NavSection } from '@/app/navigation';
import './AppShell.css';

/**
 * The frame around every signed-in screen.
 *
 * <p>A permanent sidebar from 900px, a drawer below it, a top bar with the
 * account menu, and a heading area each page fills in. The public pages keep
 * their own shell - this one is only ever rendered behind the gate.</p>
 *
 * <p><b>The navigation is a courtesy, not a control.</b> It shows a role what
 * it can use so nobody walks into a screen that would refuse them. Every route
 * is guarded again, and every request those screens make is checked by the API,
 * which is the only authoritative one of the three.</p>
 */
export function AppShell() {
  const { user, signOut } = useAuth();
  const location = useLocation();

  const [drawerOpen, setDrawerOpen] = useState(false);

  // The drawer is a detour, not a place: changing page closes it.
  useEffect(() => {
    setDrawerOpen(false);
  }, [location.pathname]);

  // Escape closes it, which is what an overlay is expected to do.
  useEffect(() => {
    if (!drawerOpen) {
      return;
    }

    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setDrawerOpen(false);
      }
    };

    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [drawerOpen]);

  if (user === null) {
    // ProtectedRoute does not render this without an account. Belt and braces.
    return null;
  }

  const sections = navigationFor(user.role);

  return (
    <div className="sl-shell-app">
      <header className="sl-shell-app__bar">
        <button
          type="button"
          className="sl-shell-app__menu"
          onClick={() => setDrawerOpen(true)}
          aria-expanded={drawerOpen}
          aria-controls="sl-drawer"
        >
          <span aria-hidden="true">&#9776;</span>
          <span className="sl-visually-hidden">Open the menu</span>
        </button>

        <Link className="sl-shell-app__brand" to="/app">
          <span className="sl-shell-app__mark" aria-hidden="true" />
          <span className="sl-shell-app__wordmark">SMARTLIB</span>
        </Link>

        <span className="sl-shell-app__bar-spacer" />

        <AccountMenu
          username={user.username}
          role={roleLabel(user.role)}
          onSignOut={() => void signOut()}
        />
      </header>

      <div className="sl-shell-app__body">
        <nav className="sl-sidebar" aria-label="Sections">
          <SectionList sections={sections} />
        </nav>

        {drawerOpen && (
          <>
            {/*
              * Decorative, and hidden from assistive technology on purpose.
              * Clicking away is a mouse convenience; the keyboard paths are
              * Escape and the close button below, which is the one control
              * that carries the name. Two controls with the same name is a
              * worse experience than one.
              */}
            <div
              className="sl-drawer__scrim"
              aria-hidden="true"
              onClick={() => setDrawerOpen(false)}
            />

            <nav className="sl-drawer" id="sl-drawer" aria-label="Sections">
              <div className="sl-drawer__head">
                <span className="sl-shell-app__wordmark">SMARTLIB</span>
                <button
                  type="button"
                  className="sl-shell-app__menu"
                  onClick={() => setDrawerOpen(false)}
                  style={{ display: 'grid' }}
                >
                  <span aria-hidden="true">&times;</span>
                  <span className="sl-visually-hidden">Close the menu</span>
                </button>
              </div>

              <SectionList sections={sections} />
            </nav>
          </>
        )}

        <main className="sl-shell-app__page" id="main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

/** The links themselves, shared by the sidebar and the drawer. */
function SectionList({ sections }: { sections: NavSection[] }) {
  return (
    <>
      {sections.map((section) => (
        <div key={section.title ?? 'main'}>
          {section.title !== null && <p className="sl-sidebar__section-title">{section.title}</p>}
          <ul className="sl-sidebar__list">
            {section.items.map((item) => (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  end={item.end}
                  className={({ isActive }) =>
                    `sl-sidebar__link${isActive ? ' is-current' : ''}`
                  }
                  // What a screen reader announces for the page you are on.
                  aria-current={undefined}
                >
                  {item.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </>
  );
}

/** Who is signed in, and the way out. */
function AccountMenu({
  username,
  role,
  onSignOut,
}: {
  username: string;
  role: string;
  onSignOut: () => void;
}) {
  const [open, setOpen] = useState(false);
  const container = useRef<HTMLDivElement>(null);

  const close = useCallback(() => setOpen(false), []);

  // A click anywhere else, or Escape, closes it.
  useEffect(() => {
    if (!open) {
      return;
    }

    const onPointerDown = (event: MouseEvent) => {
      if (container.current !== null && !container.current.contains(event.target as Node)) {
        close();
      }
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        close();
      }
    };

    document.addEventListener('mousedown', onPointerDown);
    window.addEventListener('keydown', onKey);

    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      window.removeEventListener('keydown', onKey);
    };
  }, [open, close]);

  return (
    <div className="sl-account" ref={container}>
      <button
        type="button"
        className="sl-account__button"
        onClick={() => setOpen((current) => !current)}
        aria-expanded={open}
        aria-haspopup="menu"
      >
        <span className="sl-account__avatar" aria-hidden="true">
          {username.charAt(0).toUpperCase()}
        </span>
        <span className="sl-account__who">
          <span className="sl-account__name">{username}</span>
          <span className="sl-account__role">{role}</span>
        </span>
        <span className="sl-visually-hidden">Your account</span>
      </button>

      {open && (
        <div className="sl-account__menu" role="menu">
          <div className="sl-account__heading">
            <p className="sl-account__name">{username}</p>
            <p className="sl-account__role">{role}</p>
          </div>

          <Link className="sl-account__item" to="/app/profile" role="menuitem" onClick={close}>
            Profile
          </Link>
          <Link className="sl-account__item" to="/" role="menuitem" onClick={close}>
            Public site
          </Link>
          <button type="button" className="sl-account__item" role="menuitem" onClick={onSignOut}>
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}
