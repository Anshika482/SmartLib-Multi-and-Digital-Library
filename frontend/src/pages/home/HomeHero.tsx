import { AnchorButton, LinkButton } from '@/components/ui/AnchorButton';
import { SparkIcon } from '@/components/ui/icons';
import { HeroBackdrop } from './HeroBackdrop';
import './HomeHero.css';

interface HomeHeroProps {
  /** Who is signed in, or null when nobody is - the hero then pitches. */
  username: string | null;
  /**
   * How many books the API says this library holds, or null until it has said.
   *
   * The one number on this page, and it is the `totalElements` of the real
   * catalogue response - not a figure chosen to look impressive. Until it
   * arrives, or if the call fails, the line is simply not shown.
   */
  catalogueTotal: number | null;
}

/**
 * The top of the home page.
 *
 * <p>Signed in, the two calls to action move down this page rather than away
 * from it: the catalogue and the assistant both have a section below, and
 * their own screens do not exist yet. They are anchors, so they behave like
 * links - the destination shows in the status bar and the browser handles the
 * jump - and each becomes a route the day that route is built.</p>
 *
 * <p>Signed out, the second becomes the way in: registering. Signing in sits
 * in the header for somebody who already has an account, which keeps the hero
 * pointed at the visitor who does not.</p>
 */
export function HomeHero({ username, catalogueTotal }: HomeHeroProps) {
  const signedIn = username !== null;
  return (
    <section className="sl-hero" aria-labelledby="hero-title">
      <HeroBackdrop />

      <div className="sl-hero__inner">
        <p className="sl-hero__eyebrow sl-hero__reveal sl-hero__reveal--1">
          <SparkIcon width="14" height="14" aria-hidden="true" />
          The library platform
        </p>

        <h1 className="sl-hero__title sl-hero__reveal sl-hero__reveal--2" id="hero-title">
          One Platform. Multiple Libraries. <em>Smarter Learning.</em>
        </h1>

        <p className="sl-hero__lead sl-hero__reveal sl-hero__reveal--3">
          {signedIn
            ? `Welcome back, ${username}. Browse the shelves, pick up where you left off in anything available online, and ask the assistant when you would rather not search.`
            : 'One platform for many libraries. Browse the shelves, read what is available online, and ask the assistant instead of searching - sign in to your library to begin.'}
        </p>

        <div className="sl-hero__actions sl-hero__reveal sl-hero__reveal--4">
          <AnchorButton href="#how-it-works">Explore SmartLib</AnchorButton>
          {signedIn ? (
            <AnchorButton href="#catalogue" variant="ghost">
              Your shelves
            </AnchorButton>
          ) : (
            <LinkButton to="/register" variant="ghost">
              Create an account
            </LinkButton>
          )}
        </div>

        {catalogueTotal !== null && (
          <p className="sl-hero__note sl-hero__reveal sl-hero__reveal--4">
            <span className="sl-hero__note-dot" aria-hidden="true" />
            {catalogueTotal.toLocaleString()} {catalogueTotal === 1 ? 'title' : 'titles'} catalogued in your library
          </p>
        )}
      </div>
    </section>
  );
}
