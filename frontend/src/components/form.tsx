import { forwardRef, useId, useRef, useState, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import { Eye, EyeOff, FileUp, X } from 'lucide-react';
import { cn } from '@/lib/cn';
import { bytes } from '@/lib/format';

export function Field({ label, htmlFor, error, hint, required, children, className }:
  { label: ReactNode; htmlFor: string; error?: string; hint?: ReactNode; required?: boolean; children: ReactNode; className?: string }) {
  return (
    <div className={cn('space-y-1.5', className)}>
      <label htmlFor={htmlFor} className="block text-sm font-medium text-fg/90">
        {label}
        {required && <span className="ml-0.5 text-danger" aria-hidden>*</span>}
      </label>
      {children}
      {error ? (
        <p id={`${htmlFor}-error`} className="text-xs font-medium text-danger" role="alert">{error}</p>
      ) : hint ? (
        <p id={`${htmlFor}-hint`} className="text-xs text-faint">{hint}</p>
      ) : null}
    </div>
  );
}

type InputProps = InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean; leading?: ReactNode };

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input({ className, invalid, leading, id, ...rest }, ref) {
  const input = (
    <input ref={ref} id={id} aria-invalid={invalid || undefined}
      aria-describedby={invalid && id ? `${id}-error` : undefined}
      className={cn('input', leading && 'pl-10', className)} {...rest} />
  );
  if (!leading) return input;
  return (
    <div className="relative">
      <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-faint">{leading}</span>
      {input}
    </div>
  );
});

export const PasswordInput = forwardRef<HTMLInputElement, InputProps>(function PasswordInput({ className, ...rest }, ref) {
  const [show, setShow] = useState(false);
  return (
    <div className="relative">
      <Input ref={ref} type={show ? 'text' : 'password'} className={cn('pr-11', className)} {...rest} />
      <button type="button" onClick={() => setShow((s) => !s)} aria-label={show ? 'Hide password' : 'Show password'}
        className="absolute right-1.5 top-1/2 flex size-8 -translate-y-1/2 items-center justify-center rounded-lg text-faint hover:bg-white/5 hover:text-fg">
        {show ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
      </button>
    </div>
  );
});

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement> & { invalid?: boolean }>(
  function Select({ className, invalid, children, ...rest }, ref) {
    return (
      <select ref={ref} aria-invalid={invalid || undefined} className={cn('input', className)} {...rest}>
        {children}
      </select>
    );
  },
);

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement> & { invalid?: boolean }>(
  function Textarea({ className, invalid, ...rest }, ref) {
    return <textarea ref={ref} aria-invalid={invalid || undefined} className={cn('input', className)} {...rest} />;
  },
);

export const Checkbox = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement> & { label: ReactNode; invalid?: boolean }>(
  function Checkbox({ label, invalid, id, className, ...rest }, ref) {
    const auto = useId();
    const cid = id ?? auto;
    return (
      <label htmlFor={cid} className={cn('flex cursor-pointer items-start gap-2.5 text-sm text-muted', className)}>
        <input ref={ref} id={cid} type="checkbox" aria-invalid={invalid || undefined}
          className="mt-0.5 size-4 shrink-0 cursor-pointer rounded border-line bg-ink accent-[#1688ff]" {...rest} />
        <span>{label}</span>
      </label>
    );
  },
);

/** Six-box OTP entry with paste support, auto-advance and backspace navigation. */
export function OtpInput({ value, onChange, length = 6, disabled, invalid, autoFocus }:
  { value: string; onChange: (v: string) => void; length?: number; disabled?: boolean; invalid?: boolean; autoFocus?: boolean }) {
  const refs = useRef<(HTMLInputElement | null)[]>([]);
  const digits = Array.from({ length }, (_, i) => value[i] ?? '');
  const set = (i: number, d: string) => {
    const next = digits.slice();
    next[i] = d;
    onChange(next.join('').slice(0, length));
  };
  return (
    <div className="flex justify-center gap-2 sm:gap-3" role="group" aria-label="6-digit verification code">
      {digits.map((d, i) => (
        <input key={i} ref={(el) => { refs.current[i] = el; }} value={d} disabled={disabled} inputMode="numeric" autoComplete={i === 0 ? 'one-time-code' : 'off'}
          autoFocus={autoFocus && i === 0} aria-label={`Digit ${i + 1}`} aria-invalid={invalid || undefined} maxLength={1}
          className={cn('input h-13 w-11 text-center text-xl font-bold tracking-wider sm:h-14 sm:w-12', d && 'border-accent/60')}
          onChange={(e) => {
            const v = e.target.value.replace(/\D/g, '');
            if (!v) {
              set(i, '');
              return;
            }
            if (v.length > 1) {
              onChange((digits.slice(0, i).join('') + v).slice(0, length));
              refs.current[Math.min(length - 1, i + v.length)]?.focus();
              return;
            }
            set(i, v);
            refs.current[i + 1]?.focus();
          }}
          onKeyDown={(e) => {
            if (e.key === 'Backspace' && !digits[i] && i > 0) refs.current[i - 1]?.focus();
            if (e.key === 'ArrowLeft' && i > 0) refs.current[i - 1]?.focus();
            if (e.key === 'ArrowRight' && i < length - 1) refs.current[i + 1]?.focus();
          }}
          onPaste={(e) => {
            const text = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, length);
            if (text) {
              e.preventDefault();
              onChange(text);
              refs.current[Math.min(length - 1, text.length)]?.focus();
            }
          }} />
      ))}
    </div>
  );
}

export function FileUpload({ id, accept, maxMb, file, onFile, hint }:
  { id: string; accept: string; maxMb: number; file: File | null; onFile: (f: File | null) => void; hint?: string }) {
  const [drag, setDrag] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pick = (f: File | undefined | null) => {
    setError(null);
    if (!f) return;
    if (f.size > maxMb * 1024 * 1024) {
      setError(`File is larger than ${maxMb} MB`);
      return;
    }
    onFile(f);
  };
  return (
    <div>
      {file ? (
        <div className="flex items-center gap-3 rounded-xl border border-line bg-ink/60 p-3">
          <FileUp className="size-5 text-accent-2" aria-hidden />
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium">{file.name}</p>
            <p className="text-xs text-faint">{bytes(file.size)}</p>
          </div>
          <button type="button" onClick={() => onFile(null)} aria-label="Remove file" className="btn btn-ghost btn-sm btn-icon"><X className="size-4" /></button>
        </div>
      ) : (
        <label htmlFor={id} onDragOver={(e) => { e.preventDefault(); setDrag(true); }} onDragLeave={() => setDrag(false)}
          onDrop={(e) => { e.preventDefault(); setDrag(false); pick(e.dataTransfer.files?.[0]); }}
          className={cn('flex cursor-pointer flex-col items-center justify-center gap-2 rounded-xl border border-dashed p-6 text-center transition',
            drag ? 'border-accent-2 bg-accent/10' : 'border-line-strong bg-ink/40 hover:border-accent/60')}>
          <FileUp className="size-6 text-accent-2" aria-hidden />
          <span className="text-sm font-medium">Drop a file here or <span className="link">browse</span></span>
          <span className="text-xs text-faint">{hint ?? `Max ${maxMb} MB`}</span>
          <input id={id} type="file" accept={accept} className="sr-only" onChange={(e) => pick(e.target.files?.[0])} />
        </label>
      )}
      {error && <p className="mt-1.5 text-xs text-danger" role="alert">{error}</p>}
    </div>
  );
}

/** Maps server field errors (VALIDATION_FAILED) onto react-hook-form fields. */
export function applyFieldErrors(fieldErrors: { field: string; message: string }[], setError: (name: never, e: { message: string }) => void) {
  fieldErrors.forEach((fe) => setError(fe.field as never, { message: fe.message }));
}
