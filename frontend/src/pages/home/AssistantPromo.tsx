import { Section } from '@/components/ui/Section';
import { AnchorButton } from '@/components/ui/AnchorButton';
import { SparkIcon } from '@/components/ui/icons';
import { availabilityOf } from '@/components/catalogue/availability';
import type { BookSummary } from '@/types/api';
import './AssistantPromo.css';

const POINTS = [
  'Answers from your library’s catalogue, never another’s.',
  'Never sees member records, loans, fines or file locations.',
  'Says it does not know rather than inventing a title.',
];

/**
 * What the assistant is for.
 *
 * <p>The sample exchange is labelled an example and is built from a real book
 * off this library's own shelf: the question names a title the catalogue
 * actually holds, and the answer states that title's real copy counts. When
 * the catalogue is empty or still loading there is no answer bubble at all -
 * a plausible-looking conversation about a book nobody owns would be exactly
 * the kind of invention this section promises the assistant will not do.</p>
 */
export function AssistantPromo({ sample }: { sample: BookSummary | null }) {
  const availability = sample === null ? null : availabilityOf(sample);

  return (
    <Section
      id="assistant"
      eyebrow="AI assistant"
      title="Ask, instead of searching"
      lead="A question in plain words, answered from this library's own shelves."
    >
      <div className="sl-assistant sl-panel">
        <div className="sl-assistant__copy">
          <h3 className="sl-assistant__title">It only knows your library</h3>
          <p className="sl-assistant__text">
            The assistant is handed a short list of facts about your catalogue and nothing else - no database,
            no member records, and no way to reach another library&rsquo;s shelves.
          </p>

          <ul className="sl-assistant__points">
            {POINTS.map((point) => (
              <li key={point}>
                <span className="sl-assistant__tick" aria-hidden="true">
                  &#10003;
                </span>
                <span>{point}</span>
              </li>
            ))}
          </ul>

          <div className="sl-assistant__actions">
            <AnchorButton href="#catalogue" variant="ghost">
              Browse the catalogue instead
            </AnchorButton>
          </div>
        </div>

        <div className="sl-assistant__demo">
          <p className="sl-assistant__demo-head">
            <SparkIcon width="14" height="14" />
            Example
          </p>

          {sample === null || availability === null ? (
            <>
              <p className="sl-bubble sl-bubble--asked">Do you have anything on thermodynamics?</p>
              <p className="sl-assistant__typing">
                {/* aria-label on a paragraph is unreliable; real text is not. */}
                <span className="sl-visually-hidden">The assistant is composing a reply</span>
                <span aria-hidden="true" />
                <span aria-hidden="true" />
                <span aria-hidden="true" />
              </p>
            </>
          ) : (
            <>
              <p className="sl-bubble sl-bubble--asked">Do you have &ldquo;{sample.title}&rdquo;?</p>
              <p className="sl-bubble sl-bubble--answered">
                Yes &mdash; <strong>{sample.title}</strong> by {sample.author} is in the catalogue.{' '}
                {availability.detail}.
              </p>
            </>
          )}
        </div>
      </div>
    </Section>
  );
}
