import { useId, type InputHTMLAttributes } from 'react';
import './TextField.css';

interface TextFieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  /** Shown under the field and announced; also marks the field invalid. */
  error?: string;
  hint?: string;
}

/**
 * A labelled input.
 *
 * <p>The label is always a real {@code <label>} tied to the input, never a
 * placeholder pretending to be one: a placeholder disappears the moment
 * somebody types, which is exactly when they need to know what the field
 * was.</p>
 */
export function TextField({ label, error, hint, id, className, ...rest }: TextFieldProps) {
  const generatedId = useId();
  const fieldId = id ?? generatedId;
  const messageId = `${fieldId}-message`;
  const message = error ?? hint;

  return (
    <div className={['sl-field', error !== undefined ? 'is-invalid' : '', className ?? ''].filter(Boolean).join(' ')}>
      <label className="sl-field__label" htmlFor={fieldId}>
        {label}
      </label>
      <input
        id={fieldId}
        className="sl-field__input"
        aria-invalid={error !== undefined}
        aria-describedby={message === undefined ? undefined : messageId}
        {...rest}
      />
      {message !== undefined && (
        <p id={messageId} className="sl-field__message" role={error !== undefined ? 'alert' : undefined}>
          {message}
        </p>
      )}
    </div>
  );
}
