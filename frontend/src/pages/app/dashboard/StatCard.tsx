import { Link } from 'react-router-dom';
import './StatCard.css';

interface StatCardProps {
  label: string;
  /** The real figure. Never a placeholder, never rounded up. */
  value: number | string;
  note?: string;
  /** Where this figure is explained in full. */
  to?: string;
  /** Draws the eye when the number is one somebody should act on. */
  attention?: boolean;
}

/**
 * One figure, with a way through to the screen that explains it.
 *
 * <p>A card with a destination is a link, so it behaves like one - middle
 * click, keyboard, the status bar. A card without one is a plain panel rather
 * than a control that does nothing.</p>
 */
export function StatCard({ label, value, note, to, attention = false }: StatCardProps) {
  const body = (
    <>
      <span className="sl-stat__label">{label}</span>
      <span className={`sl-stat__value${attention ? ' sl-stat__value--attention' : ''}`}>
        {typeof value === 'number' ? value.toLocaleString() : value}
      </span>
      {note !== undefined && <span className="sl-stat__note">{note}</span>}
    </>
  );

  if (to === undefined) {
    return <div className="sl-stat sl-panel">{body}</div>;
  }

  return (
    <Link className="sl-stat sl-panel" to={to}>
      {body}
    </Link>
  );
}
