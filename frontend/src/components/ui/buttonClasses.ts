export type ButtonVariant = 'primary' | 'ghost' | 'quiet';

/**
 * The class list a button wears.
 *
 * <p>Lives apart from the components so a <button> and an <a> can look the
 * same without one importing the other. Which element to use is a question
 * about behaviour - a link goes somewhere, a button does something - and it
 * should never be decided by which one was easier to style.</p>
 */
export function buttonClasses(
  variant: ButtonVariant = 'primary',
  options: { fullWidth?: boolean; busy?: boolean; className?: string } = {},
): string {
  return [
    'sl-button',
    `sl-button--${variant}`,
    options.fullWidth === true ? 'sl-button--block' : '',
    options.busy === true ? 'is-busy' : '',
    options.className ?? '',
  ]
    .filter(Boolean)
    .join(' ');
}
