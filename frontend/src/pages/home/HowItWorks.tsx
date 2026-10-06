import { Section } from '@/components/ui/Section';
import './HowItWorks.css';

/**
 * How SmartLib works, in three steps.
 *
 * <p>Each step describes something the system actually does today. The third
 * covers both ways an account appears now: somebody registers themselves, or a
 * librarian enrols them. Staff accounts are applications either way, which is
 * what the wording keeps.</p>
 */
const STEPS = [
  {
    title: 'A library joins',
    text: 'Each institution gets its own space on the platform, with its own shelves, staff and members. An administrator sets it up once.',
  },
  {
    title: 'Its catalogue goes in',
    text: 'Books, categories and anything readable online - chapters, e-books, recordings, links - are attached to the titles they belong to.',
  },
  {
    title: 'Members are enrolled',
    text: 'Members register themselves and join a library; staff apply and are approved. From then on they borrow, return, read online and ask the assistant, all in one place.',
  },
];

export function HowItWorks() {
  return (
    <Section
      id="how-it-works"
      eyebrow="How it works"
      title="Three steps from shelf to screen"
      lead="SmartLib replaces the ledger, the card catalogue and the noticeboard with one system that every branch runs on."
    >
      <ol className="sl-steps">
        {STEPS.map((step, index) => (
          <li className="sl-step sl-panel" key={step.title}>
            <span className="sl-step__number" aria-hidden="true">
              {index + 1}
            </span>
            <h3 className="sl-step__title">{step.title}</h3>
            <p className="sl-step__text">{step.text}</p>
          </li>
        ))}
      </ol>
    </Section>
  );
}
