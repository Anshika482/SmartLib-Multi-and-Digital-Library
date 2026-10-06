import './FullPageLoader.css';

/**
 * What fills the screen while a session is being checked.
 *
 * <p>A lamp warming up rather than a spinner: the same warm light the rest of
 * the interface uses, and quiet enough not to read as an error. The label is
 * announced politely so a screen reader says what is happening.</p>
 */
export function FullPageLoader({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="sl-loader" role="status" aria-live="polite">
      <span className="sl-loader__lamp" aria-hidden="true" />
      <p className="sl-loader__label">{label}</p>
    </div>
  );
}
