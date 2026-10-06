import { PageHeading } from './PageHeading';
import './PlaceholderPage.css';

interface PlaceholderPageProps {
  title: string;
  lead: string;
  /** What this screen will do, in the words of somebody who will use it. */
  coming: string[];
}

/**
 * A screen that is routed, reachable and honest about not being built yet.
 *
 * <p>One component for every not-yet-implemented page, because they differ only
 * in their words. It says plainly that the screen is coming rather than
 * pretending with empty tables and disabled buttons - a mock of a feature is
 * harder to tell from a broken one than a sentence is.</p>
 *
 * <p>Nothing here invents data. There are no counts, no rows and no charts.</p>
 */
export function PlaceholderPage({ title, lead, coming }: PlaceholderPageProps) {
  return (
    <>
      <PageHeading title={title} lead={lead} />

      <div className="sl-panel sl-placeholder">
        <p className="sl-placeholder__badge">Not built yet</p>
        <p className="sl-placeholder__text">
          This screen is routed and reachable, and the work behind it is done in a later step. When it
          arrives it will let you:
        </p>
        <ul className="sl-placeholder__list">
          {coming.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      </div>
    </>
  );
}
