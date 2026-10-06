import { LinkButton } from './AnchorButton';
import { BookshelfIcon } from './icons';
import './DataState.css';

interface SignInPromptProps {
  title: string;
  detail: string;
}

/**
 * What a section shows when nobody is signed in.
 *
 * <p>Every catalogue endpoint requires authentication, so a signed-out visitor
 * cannot be shown books or resources. This says so plainly and offers the way
 * in - rather than an error, which would suggest something is broken, or a
 * sample, which would be an invention.</p>
 *
 * <p>It borrows the states DataState already styles, so a section looks the
 * same whether it is empty, failed or waiting for a sign-in.</p>
 */
export function SignInPrompt({ title, detail }: SignInPromptProps) {
  return (
    <div className="sl-state sl-panel">
      <span className="sl-state__icon">
        <BookshelfIcon />
      </span>
      <p className="sl-state__title">{title}</p>
      <p className="sl-state__detail">{detail}</p>
      <LinkButton to="/sign-in" variant="ghost">
        Sign in
      </LinkButton>
    </div>
  );
}
