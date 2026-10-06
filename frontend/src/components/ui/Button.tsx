import type { ButtonHTMLAttributes } from 'react';
import { buttonClasses, type ButtonVariant } from './buttonClasses';
import './Button.css';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  /** Shows a working state and blocks a second submit. */
  busy?: boolean;
  fullWidth?: boolean;
}

/**
 * The application's button.
 *
 * <p>Three weights, because an interface needs a primary action, a secondary
 * one, and something that is barely a button. A busy button stays the same
 * width so a form does not jump while it submits.</p>
 */
export function Button({
  variant = 'primary',
  busy = false,
  fullWidth = false,
  disabled,
  className,
  children,
  ...rest
}: ButtonProps) {
  const classes = buttonClasses(variant, { fullWidth, busy, className });

  return (
    <button className={classes} disabled={disabled === true || busy} aria-busy={busy} {...rest}>
      <span className="sl-button__label">{children}</span>
    </button>
  );
}
