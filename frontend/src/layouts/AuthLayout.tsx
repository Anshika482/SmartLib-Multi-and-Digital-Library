import type { ReactNode } from 'react';
import './AuthLayout.css';

/**
 * The frame around signing in.
 *
 * <p>On a phone it is the form and nothing else. From tablet width a second
 * column appears with the library's own pitch - the part that makes the screen
 * feel like a place rather than a gate. Nothing in that column is needed to
 * sign in, which is why it is the part that disappears first.</p>
 */
export function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="sl-auth">
      <aside className="sl-auth__pitch">
        <div className="sl-auth__brand">
          <span className="sl-auth__mark" aria-hidden="true" />
          <span className="sl-auth__wordmark">SMARTLIB</span>
        </div>
        <h1 className="sl-auth__headline">Your library, after hours.</h1>
        <p className="sl-auth__sub">
          Borrow, return, read online and settle a fine - from the desk or from the sofa.
        </p>
        <ul className="sl-auth__notes">
          <li className="sl-auth__note">Every library's shelves kept entirely its own</li>
          <li className="sl-auth__note">Digital editions to read wherever you are</li>
          <li className="sl-auth__note">An assistant that answers from the catalogue, not from guesswork</li>
        </ul>
      </aside>

      <main className="sl-auth__panel">
        <div className="sl-auth__brand sl-auth__brand--compact">
          <span className="sl-auth__mark" aria-hidden="true" />
          <span className="sl-auth__wordmark">SMARTLIB</span>
        </div>
        <section className="sl-panel sl-auth__card">{children}</section>
      </main>
    </div>
  );
}
