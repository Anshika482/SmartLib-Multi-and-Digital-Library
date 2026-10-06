import type { AnchorHTMLAttributes, ReactNode } from 'react';
import { Link, type LinkProps } from 'react-router-dom';
import { buttonClasses, type ButtonVariant } from './buttonClasses';
import './Button.css';

interface CommonProps {
  variant?: ButtonVariant;
  fullWidth?: boolean;
  children: ReactNode;
}

type AnchorProps = CommonProps & AnchorHTMLAttributes<HTMLAnchorElement>;

/**
 * A link that looks like a button.
 *
 * <p>Still a real anchor: it opens in a new tab on a middle click, it can be
 * copied, and a screen reader announces it as a link rather than a control.
 * A button that navigates lies about all three.</p>
 */
export function AnchorButton({ variant = 'primary', fullWidth = false, className, children, ...rest }: AnchorProps) {
  return (
    <a className={buttonClasses(variant, { fullWidth, className })} {...rest}>
      <span className="sl-button__label">{children}</span>
    </a>
  );
}

type RouteProps = CommonProps & LinkProps;

/** The same, for a destination inside this application. */
export function LinkButton({ variant = 'primary', fullWidth = false, className, children, ...rest }: RouteProps) {
  return (
    <Link className={buttonClasses(variant, { fullWidth, className })} {...rest}>
      <span className="sl-button__label">{children}</span>
    </Link>
  );
}
