import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Check, PackageOpen, X } from 'lucide-react';
import type { RequestView } from '@/api/types';
import { ConfirmDialog } from '@/components/modal';
import { Button } from '@/components/ui';
import { date } from '@/lib/format';
import { useStaff } from './useStaff';

/** Approve / reject / issue with confirmation; every action calls the API and the server re-validates scope and state. */
export function RequestActions({ request }: { request: RequestView }) {
  const { api } = useStaff();
  const qc = useQueryClient();
  const [mode, setMode] = useState<'approve' | 'reject' | 'issue' | null>(null);
  const done = (msg: string) => {
    toast.success(msg);
    qc.invalidateQueries({ queryKey: ['manage'] });
  };
  const approve = useMutation({ mutationFn: () => api.approve(request.id), onSuccess: (r) => done(`Approved — copy ${r.assignedCopy} reserved until ${date(r.pickupDeadline)}`) });
  const reject = useMutation({ mutationFn: (reason: string) => api.reject(request.id, reason), onSuccess: () => done('Request rejected') });
  const issue = useMutation({ mutationFn: () => api.issue(request.id), onSuccess: (l) => done(`Issued — due ${date(l.dueDate)}`) });
  const who = request.member?.fullName ?? 'the member';
  return (
    <div className="flex flex-wrap justify-end gap-1.5">
      {request.status === 'PENDING' && (
        <Button size="sm" variant="success" icon={<Check className="size-3.5" />} onClick={() => setMode('approve')} disabled={request.availableCopies === 0}
          title={request.availableCopies === 0 ? 'No copy is available right now' : undefined}>Approve</Button>
      )}
      {request.status === 'APPROVED' && <Button size="sm" icon={<PackageOpen className="size-3.5" />} onClick={() => setMode('issue')}>Issue</Button>}
      {(request.status === 'PENDING' || request.status === 'APPROVED') && (
        <Button size="sm" variant="soft-danger" icon={<X className="size-3.5" />} onClick={() => setMode('reject')}>Reject</Button>
      )}
      <ConfirmDialog open={mode === 'approve'} onOpenChange={(o) => !o && setMode(null)} tone="success" confirmLabel="Approve & reserve copy"
        title="Approve this request?" description={`“${request.book.title}” for ${who}. An available copy will be reserved for pickup.`}
        onConfirm={() => approve.mutateAsync()} />
      <ConfirmDialog open={mode === 'issue'} onOpenChange={(o) => !o && setMode(null)} confirmLabel="Issue book"
        title="Issue this book?" description={`Hand copy ${request.assignedCopy ?? ''} of “${request.book.title}” to ${who}. The due date is set from your library's loan period.`}
        onConfirm={() => issue.mutateAsync()} />
      <ConfirmDialog open={mode === 'reject'} onOpenChange={(o) => !o && setMode(null)} tone="danger" confirmLabel="Reject request"
        title="Reject this request?" description={`${who} will be notified with your reason.`}
        reason={{ label: 'Reason', placeholder: 'e.g. Reserved for course reserves this week' }} onConfirm={(r) => reject.mutateAsync(r)} />
    </div>
  );
}
