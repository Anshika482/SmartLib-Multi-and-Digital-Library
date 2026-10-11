import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { CheckCircle2 } from 'lucide-react';
import { meApi } from '@/api/endpoints';
import { isApiError } from '@/api/http';
import type { BookDetails, HoldingDetail, RequestView } from '@/api/types';
import { Modal } from '@/components/modal';
import { Alert, Button, KeyValue } from '@/components/ui';
import { Field, Textarea } from '@/components/form';
import { BookCover } from '@/components/book';
import { money } from '@/lib/format';

/** Confirms Book + selected Library + Member before creating the request. The backend re-validates everything. */
export function RequestDialog({ book, holding, onClose }: { book: BookDetails; holding: HoldingDetail | null; onClose: () => void }) {
  const qc = useQueryClient();
  const [note, setNote] = useState('');
  const [done, setDone] = useState<RequestView | null>(null);
  // Reset only when a different library is chosen, not when the same holding is re-rendered.
  const holdingId = holding?.holdingId;
  useEffect(() => {
    if (holdingId != null) {
      setNote('');
      setDone(null);
    }
  }, [holdingId]);
  const create = useMutation({
    mutationFn: () => meApi.createRequest({ bookId: book.id, libraryId: holding!.libraryId, note: note.trim() || undefined }),
    onSuccess: (r) => {
      setDone(r);
      toast.success(`Request sent to ${r.library.name}`);
      qc.invalidateQueries({ queryKey: ['me'] });
      qc.invalidateQueries({ queryKey: ['book', book.id] });
    },
  });
  const err = create.error && isApiError(create.error) ? create.error : null;
  return (
    <Modal open={!!holding} onOpenChange={(o) => !o && !create.isPending && onClose()} title={done ? 'Request sent' : 'Request this book'}
      description={done ? undefined : 'Your request goes only to the library you selected.'}
      footer={done ? (
        <><Button variant="ghost" onClick={onClose}>Close</Button><Link to="/app/member/requests" className="btn btn-primary">View my requests</Link></>
      ) : (
        <><Button variant="ghost" onClick={onClose} disabled={create.isPending}>Cancel</Button>
          <Button loading={create.isPending} onClick={() => create.mutate()}>Confirm request</Button></>
      )}>
      {holding && (
        <div className="space-y-4">
          <div className="flex gap-4">
            <BookCover book={book} className="w-20 shrink-0" />
            <div className="min-w-0">
              <p className="font-semibold">{book.title}</p>
              <p className="text-sm text-muted">{book.authors.join(', ')}</p>
              <p className="mt-2 text-sm">From <b className="text-accent-2">{holding.libraryName}</b></p>
            </div>
          </div>
          {done ? (
            <Alert tone="success" title="Awaiting approval">
              {holding.libraryName}'s librarian will review your request. Once approved, a copy is reserved and you'll have {holding.pickupWindowDays} days to collect it.
              <CheckCircle2 className="sr-only" />
            </Alert>
          ) : (
            <>
              <KeyValue items={[
                { label: 'Available copies', value: `${holding.availableCopies} of ${holding.totalCopies}` },
                { label: 'Loan period', value: `${holding.loanPeriodDays} days` },
                { label: 'Pickup window', value: `${holding.pickupWindowDays} days after approval` },
                { label: 'Late fee', value: `${money(holding.finePerDayPaise)} per day` },
              ]} />
              <Field label="Note for the librarian" htmlFor="request-note" hint="Optional">
                <Textarea id="request-note" rows={2} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
              </Field>
              {err && (
                <Alert tone="error" action={err.code === 'OUTSTANDING_FINES' ? <Link to="/app/member/fines" className="link text-sm">Pay fines →</Link>
                  : err.code === 'REQUEST_ALREADY_EXISTS' || err.code === 'ALREADY_BORROWED' ? <Link to="/app/member/requests" className="link text-sm">View my requests →</Link> : undefined}>
                  {err.message}
                </Alert>
              )}
            </>
          )}
        </div>
      )}
    </Modal>
  );
}
