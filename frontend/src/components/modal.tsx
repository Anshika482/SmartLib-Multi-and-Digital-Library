import { useEffect, useState, type ReactNode } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { X } from 'lucide-react';
import { cn } from '@/lib/cn';
import { Alert, Button } from './ui';
import { Field, Textarea } from './form';
import { isApiError } from '@/api/http';

export function Modal({ open, onOpenChange, title, description, children, footer, size = 'md' }:
  { open: boolean; onOpenChange: (o: boolean) => void; title: ReactNode; description?: ReactNode; children?: ReactNode; footer?: ReactNode; size?: 'sm' | 'md' | 'lg' | 'xl' }) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/65 backdrop-blur-sm data-[state=open]:animate-[fadeIn_.15s_ease-out]" />
        <Dialog.Content
          className={cn('glass fixed left-1/2 top-1/2 z-50 flex max-h-[calc(100dvh-2rem)] w-[calc(100vw-2rem)] -translate-x-1/2 -translate-y-1/2 flex-col p-0 focus:outline-none',
            { sm: 'max-w-sm', md: 'max-w-lg', lg: 'max-w-2xl', xl: 'max-w-4xl' }[size])}>
          <div className="flex items-start justify-between gap-4 border-b border-line px-5 py-4">
            <div className="min-w-0">
              <Dialog.Title className="text-lg font-semibold text-fg">{title}</Dialog.Title>
              {description ? <Dialog.Description className="mt-1 text-sm text-muted">{description}</Dialog.Description>
                : <Dialog.Description className="sr-only">Dialog</Dialog.Description>}
            </div>
            <Dialog.Close className="btn btn-ghost btn-sm btn-icon -mr-2" aria-label="Close"><X className="size-4" /></Dialog.Close>
          </div>
          <div className="overflow-y-auto px-5 py-4">{children}</div>
          {footer && <div className="flex flex-wrap justify-end gap-2 border-t border-line px-5 py-3.5">{footer}</div>}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

/** Confirmation for state-changing actions; optionally collects a required reason. Shows server errors inline. */
export function ConfirmDialog({ open, onOpenChange, title, description, confirmLabel = 'Confirm', tone = 'primary', reason, onConfirm, children }:
  {
    open: boolean; onOpenChange: (o: boolean) => void; title: ReactNode; description?: ReactNode; confirmLabel?: string;
    tone?: 'primary' | 'danger' | 'success'; reason?: { label: string; placeholder?: string; minLength?: number };
    onConfirm: (reason: string) => Promise<unknown>; children?: ReactNode;
  }) {
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (open) {
      setText('');
      setError(null);
    }
  }, [open]);
  const tooShort = reason ? text.trim().length < (reason.minLength ?? 3) : false;
  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await onConfirm(text.trim());
      onOpenChange(false);
    } catch (e) {
      setError(isApiError(e) ? e.message : 'Something went wrong. Please try again.');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal open={open} onOpenChange={(o) => !busy && onOpenChange(o)} title={title} description={description} size="sm"
      footer={<>
        <Button variant="ghost" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
        <Button variant={tone} loading={busy} disabled={tooShort} onClick={submit}>{confirmLabel}</Button>
      </>}>
      <div className="space-y-4">
        {children}
        {reason && (
          <Field label={reason.label} htmlFor="confirm-reason" required>
            <Textarea id="confirm-reason" value={text} onChange={(e) => setText(e.target.value)} placeholder={reason.placeholder} maxLength={500} />
          </Field>
        )}
        {error && <Alert tone="error">{error}</Alert>}
      </div>
    </Modal>
  );
}
