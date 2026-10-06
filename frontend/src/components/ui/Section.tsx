import type { ReactNode } from 'react';
import './Section.css';

interface SectionProps {
  /** Needed: the heading is tied to the section with aria-labelledby. */
  id: string;
  eyebrow?: string;
  title: string;
  lead?: string;
  /** Sits opposite the heading on a wide screen, under it on a narrow one. */
  aside?: ReactNode;
  children: ReactNode;
}

/**
 * One band of the page: a heading, an optional line under it, and content.
 *
 * <p>Every section on the home page is one of these, so their rhythm, their
 * heading level and the way they are labelled cannot drift apart. The heading
 * is always an h2 - the page has exactly one h1, in the hero.</p>
 */
export function Section({ id, eyebrow, title, lead, aside, children }: SectionProps) {
  const headingId = `${id}-title`;

  return (
    <section className="sl-section" id={id} aria-labelledby={headingId}>
      <header className={`sl-section__head${aside === undefined ? '' : ' sl-section__head--split'}`}>
        <div className="sl-section__head-text">
          {eyebrow !== undefined && <p className="sl-section__eyebrow">{eyebrow}</p>}
          <h2 className="sl-section__title" id={headingId}>
            {title}
          </h2>
          {lead !== undefined && <p className="sl-section__lead">{lead}</p>}
        </div>
        {aside}
      </header>

      {children}
    </section>
  );
}
