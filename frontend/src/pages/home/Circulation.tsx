import type { CSSProperties } from 'react';
import { Section } from '@/components/ui/Section';
import { CatalogueIcon, LibrariesIcon, ReaderIcon } from '@/components/ui/icons';
import './Circulation.css';

/**
 * Loans, fines and payments.
 *
 * <p>Describes the circulation side of the system for a visitor deciding
 * whether it does what their library needs. No numbers appear here: how many
 * loans a deployment has run, or what it has collected in fines, is nobody's
 * business but the library's - and inventing a figure to look established
 * would be worse than showing none.</p>
 */
const CARDS = [
  {
    icon: <CatalogueIcon />,
    title: 'Issue and return',
    text: 'A librarian issues a copy to a member and takes it back at the desk. Availability updates as it happens, so the catalogue always matches the shelf.',
    ink: 'var(--lamp-300)',
  },
  {
    icon: <LibrariesIcon />,
    title: 'Fines, calculated not guessed',
    text: 'An overdue loan accrues against the library’s own rules. The amount is worked out from the dates, never typed in by hand.',
    ink: 'var(--accent-300)',
  },
  {
    icon: <ReaderIcon />,
    title: 'Payment, recorded either way',
    text: 'Settle at the desk or through the online gateway. Both land in the same ledger, and every settlement is written to the audit trail.',
    ink: 'var(--violet-400)',
  },
];

export function Circulation() {
  return (
    <Section
      id="circulation"
      eyebrow="Loans, fines and payments"
      title="The desk work, handled"
      lead="Borrowing is the part a library does a thousand times a week. SmartLib makes each one a record rather than a note."
    >
      <div className="sl-circ">
        {CARDS.map((card) => (
          <article
            className="sl-circ__card sl-panel"
            key={card.title}
            style={{ '--circ-ink': card.ink } as CSSProperties}
          >
            <span className="sl-circ__icon" aria-hidden="true">
              {card.icon}
            </span>
            <h3 className="sl-circ__title">{card.title}</h3>
            <p className="sl-circ__text">{card.text}</p>
          </article>
        ))}
      </div>

      <p className="sl-circ__note">
        Loans, fines and payments are visible only to the member they belong to and the staff of their own
        library. None of it is reachable from this page, signed in or not.
      </p>
    </Section>
  );
}
