import type { ReactNode } from 'react';
import './Badge.css';

export type BadgeTone = 'available' | 'low' | 'out' | 'accent' | 'violet';

interface BadgeProps {
  tone?: BadgeTone;
  /** Drops the leading dot, for badges that are a label rather than a status. */
  plain?: boolean;
  /** What a screen reader should hear instead of the short visible text. */
  srLabel?: string;
  children: ReactNode;
}

/**
 * A short status, in a pill.
 *
 * <p>Colour is never the only carrier: each badge also says its state in
 * words, so it survives being read aloud, printed, or seen by someone who
 * cannot tell the amber from the green.</p>
 */
export function Badge({ tone = 'accent', plain = false, srLabel, children }: BadgeProps) {
  return (
    <span className={`sl-badge sl-badge--${tone}${plain ? ' sl-badge--plain' : ''}`}>
      {srLabel === undefined ? (
        children
      ) : (
        <>
          <span aria-hidden="true">{children}</span>
          <span className="sl-visually-hidden">{srLabel}</span>
        </>
      )}
    </span>
  );
}
