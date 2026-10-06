import { useAuth } from '@/auth/useAuth';
import { FullPageLoader } from '@/components/ui/FullPageLoader';
import { useAsync } from '@/hooks/useAsync';
import { catalogueService } from '@/services/catalogueService';
import { HomeHero } from './home/HomeHero';
import { PlatformHighlights } from './home/PlatformHighlights';
import { HowItWorks } from './home/HowItWorks';
import { MultiLibrary } from './home/MultiLibrary';
import { PublicCatalogue } from './home/PublicCatalogue';
import { Circulation } from './home/Circulation';
import { RoleExperience } from './home/RoleExperience';
import { FinalCta } from './home/FinalCta';
import { FeaturedBooks } from './home/FeaturedBooks';
import { DigitalReadingPreview } from './home/DigitalReadingPreview';
import { AssistantPromo } from './home/AssistantPromo';
import './HomePage.css';

/** How many books the shelf preview shows. Four across, two rows at most. */
const PREVIEW_SIZE = 8;

/**
 * The home page.
 *
 * <p>The public face of SmartLib, and the signed-in landing, in one page. A
 * visitor walks hero, how it works, multi-library, a real catalogue search,
 * digital reading, the assistant, circulation, roles, and the decision at the
 * end. Signed in, the catalogue band becomes their own shelves instead.</p>
 *
 * <p>Every band that shows data shows real data or says plainly that it has
 * none. Nothing on this page is a sample.</p>
 *
 * <p>The catalogue is fetched once here and shared: the hero quotes its total,
 * the shelf renders its rows, and the assistant's example is built from its
 * first book. Three sections, one request.</p>
 *
 * <p><b>This page is public.</b> Signed out it still renders in full - the
 * hero, what the platform does, and the assistant - but the two sections that
 * read the catalogue ask nobody for it: those endpoints require a token, so
 * the request is skipped rather than sent and refused, and each section offers
 * the way in instead. Nothing is invented to fill the gap.</p>
 */
export function HomePage() {
  const { user, loading } = useAuth();
  const signedIn = user !== null;

  const books = useAsync(
    (signal) =>
      catalogueService.list(
        { page: 0, size: PREVIEW_SIZE, sortBy: 'availableCopies', direction: 'desc' },
        signal,
      ),
    [],
    signedIn,
  );

  // A stored token is being checked. Deciding now would show the signed-out
  // hero to somebody who is about to turn out to be signed in.
  if (loading) {
    return <FullPageLoader label="Opening your library" />;
  }

  const shelf = books.data?.content ?? [];

  return (
    <div className="sl-home">
      <HomeHero username={user?.username ?? null} catalogueTotal={books.data?.totalElements ?? null} />

      <div className="sl-shell">
        <PlatformHighlights />
          <HowItWorks />
          <MultiLibrary />

        {/*
         * Discovery. A visitor gets the public catalogue, which needs no token;
       * a signed-in person gets their own library's shelves with the copy
       * counts the public endpoint does not carry.
       */}
        {signedIn ? <FeaturedBooks books={books} signedIn /> : <PublicCatalogue />}

        <DigitalReadingPreview role={user?.role ?? null} />
        <AssistantPromo sample={shelf.find((book) => book.availableCopies > 0) ?? shelf[0] ?? null} />
        <Circulation />
        <RoleExperience />
        <FinalCta />
      </div>
    </div>
  );
}
