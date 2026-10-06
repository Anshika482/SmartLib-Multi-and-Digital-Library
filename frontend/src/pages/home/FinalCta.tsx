import { LinkButton, AnchorButton } from '@/components/ui/AnchorButton';
import './FinalCta.css';

/**
 * The end of the page, and the decision it asks for.
 *
 * <p>Registration is real now, so this offers it. The three kinds differ in
 * what they cost the person: a member joins a library and can use it at once,
 * while a librarian or an administrator is applying and waits for somebody to
 * agree. The wording says so rather than promising an instant account to
 * everybody who presses the button.</p>
 */
export function FinalCta() {
  return (
    <section className="sl-cta" aria-labelledby="cta-title">
      <h2 className="sl-cta__title" id="cta-title">
        Your library, already online.
      </h2>
      <p className="sl-cta__lead">
        Search the catalogue from here, or sign in to borrow, read what is published online and ask the
        assistant about your own shelves.
      </p>

      <div className="sl-cta__actions">
        <LinkButton to="/register">Create an account</LinkButton>
        <LinkButton to="/sign-in" variant="ghost">
          Sign In
        </LinkButton>
        <AnchorButton href="#catalogue" variant="ghost">
          Search the catalogue
        </AnchorButton>
      </div>

      <div className="sl-cta__enrol">
        <p className="sl-cta__enrol-title">Which kind of account?</p>
        <p className="sl-cta__enrol-text">
          Join a library as a <strong>member</strong> and start borrowing straight away. Applying as a{' '}
          <strong>librarian</strong> or to open a library as an <strong>administrator</strong> is reviewed
          first, and you will be told when it is approved.
        </p>
      </div>
    </section>
  );
}
