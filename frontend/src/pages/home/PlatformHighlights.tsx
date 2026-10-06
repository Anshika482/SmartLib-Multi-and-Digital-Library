import type { CSSProperties, ReactNode } from 'react';
import { Section } from '@/components/ui/Section';
import { CatalogueIcon, LibrariesIcon, ReaderIcon, SparkIcon } from '@/components/ui/icons';
import './PlatformHighlights.css';

interface Highlight {
  icon: ReactNode;
  title: string;
  text: string;
  /** The accent this card is lit with. */
  ink: string;
  glow: string;
}

/**
 * What the platform does.
 *
 * <p>Four capabilities, each describing something the backend actually
 * implements - multi-library scoping, digital resources, the assistant, and
 * catalogue search. No counts, no adoption figures and no claims about other
 * institutions: nothing here is a number, because there is no honest number
 * to put in a marketing tile.</p>
 */
const HIGHLIGHTS: Highlight[] = [
  {
    icon: <LibrariesIcon />,
    title: 'Multi-Library',
    text: 'Every branch keeps its own shelves, members and loans. Nothing crosses from one library into another.',
    ink: 'var(--lamp-300)',
    glow: 'rgba(246, 193, 119, 0.14)',
  },
  {
    icon: <ReaderIcon />,
    title: 'Digital Reading',
    text: 'PDFs, e-books, video and links attached to the books they belong to, and readable without a trip to the desk.',
    ink: 'var(--accent-300)',
    glow: 'rgba(108, 140, 255, 0.16)',
  },
  {
    icon: <SparkIcon />,
    title: 'AI Assistant',
    text: 'Ask about a book or how something works. It answers from your library’s own catalogue, and only yours.',
    ink: 'var(--violet-400)',
    glow: 'rgba(167, 139, 250, 0.16)',
  },
  {
    icon: <CatalogueIcon />,
    title: 'Smart Catalogue',
    text: 'Search by title, author or ISBN, filter by category, and see what is on the shelf right now.',
    ink: 'var(--success)',
    glow: 'rgba(94, 203, 154, 0.14)',
  },
];

export function PlatformHighlights() {
  return (
    <Section
      id="platform"
      eyebrow="The platform"
      title="Built for how a library actually runs"
      lead="Four pieces, one system - each of them doing a job the desk used to do on paper."
    >
      <ul className="sl-highlights">
        {HIGHLIGHTS.map((highlight) => (
          <li
            className="sl-highlight sl-panel"
            key={highlight.title}
            style={
              {
                '--highlight-ink': highlight.ink,
                '--highlight-glow': highlight.glow,
              } as CSSProperties
            }
          >
            <span className="sl-highlight__icon" aria-hidden="true">
              {highlight.icon}
            </span>
            <h3 className="sl-highlight__title">{highlight.title}</h3>
            <p className="sl-highlight__text">{highlight.text}</p>
          </li>
        ))}
      </ul>
    </Section>
  );
}
