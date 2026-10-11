import { AlertTriangle, Ban, CheckCircle2, CircleDashed, Clock3, PackageCheck, RotateCcw, XCircle, Hourglass, BookOpenCheck } from 'lucide-react';
import type { ReactNode } from 'react';
import { Badge, type Tone } from './ui';

const map: Record<string, [string, Tone, ReactNode]> = {
  // requests
  PENDING: ['Awaiting approval', 'amber', <Hourglass key="i" className="size-3" />],
  APPROVED: ['Ready for pickup', 'cyan', <PackageCheck key="i" className="size-3" />],
  REJECTED: ['Rejected', 'red', <XCircle key="i" className="size-3" />],
  CANCELLED: ['Cancelled', 'gray', <Ban key="i" className="size-3" />],
  EXPIRED: ['Pickup expired', 'gray', <Clock3 key="i" className="size-3" />],
  FULFILLED: ['Issued', 'green', <BookOpenCheck key="i" className="size-3" />],
  // loans
  ACTIVE: ['Borrowed', 'blue', <BookOpenCheck key="i" className="size-3" />],
  RETURNED: ['Returned', 'green', <RotateCcw key="i" className="size-3" />],
  LOST: ['Lost', 'red', <AlertTriangle key="i" className="size-3" />],
  OVERDUE: ['Overdue', 'red', <AlertTriangle key="i" className="size-3" />],
  DUE_SOON: ['Due soon', 'amber', <Clock3 key="i" className="size-3" />],
  // fines & payments
  UNPAID: ['Fine pending', 'amber', <Clock3 key="i" className="size-3" />],
  PAID: ['Paid', 'green', <CheckCircle2 key="i" className="size-3" />],
  WAIVED: ['Waived', 'violet', <CheckCircle2 key="i" className="size-3" />],
  CREATED: ['Payment started', 'gray', <CircleDashed key="i" className="size-3" />],
  FAILED: ['Failed', 'red', <XCircle key="i" className="size-3" />],
  // copies
  AVAILABLE: ['Available', 'green', <CheckCircle2 key="i" className="size-3" />],
  RESERVED: ['Reserved', 'cyan', <PackageCheck key="i" className="size-3" />],
  BORROWED: ['On loan', 'blue', <BookOpenCheck key="i" className="size-3" />],
  DAMAGED: ['Damaged', 'amber', <AlertTriangle key="i" className="size-3" />],
  WITHDRAWN: ['Withdrawn', 'gray', <Ban key="i" className="size-3" />],
  // staff / accounts
  REVOKED: ['Revoked', 'gray', <Ban key="i" className="size-3" />],
  SUSPENDED: ['Suspended', 'red', <Ban key="i" className="size-3" />],
  DISABLED: ['Disabled', 'gray', <Ban key="i" className="size-3" />],
  INVITED: ['Invited', 'cyan', <CircleDashed key="i" className="size-3" />],
  INACTIVE: ['Inactive', 'gray', <Ban key="i" className="size-3" />],
  ARCHIVED: ['Archived', 'gray', <Ban key="i" className="size-3" />],
  SUCCESS: ['Success', 'green', <CheckCircle2 key="i" className="size-3" />],
  FAILURE: ['Failure', 'red', <XCircle key="i" className="size-3" />],
};

/** Status pill with icon + text, so meaning never depends on colour alone. */
export function StatusBadge({ status, context }: { status: string; context?: 'approval' | 'account' }) {
  let key = status;
  if (context === 'approval' && status === 'PENDING') {
    return <Badge tone="amber" icon={<Hourglass className="size-3" />}>Pending approval</Badge>;
  }
  if (context === 'approval' && status === 'APPROVED') {
    return <Badge tone="green" icon={<CheckCircle2 className="size-3" />}>Approved</Badge>;
  }
  if (context === 'account' && status === 'ACTIVE') {
    return <Badge tone="green" icon={<CheckCircle2 className="size-3" />}>Active</Badge>;
  }
  if (!map[key]) key = 'CANCELLED';
  const [label, tone, icon] = map[key] ?? [status, 'gray', null];
  return <Badge tone={tone} icon={icon}>{map[status] ? label : status.replace(/_/g, ' ').toLowerCase()}</Badge>;
}

export function LoanStatusBadge({ loan }: { loan: { status: string; overdue: boolean; dueSoon: boolean } }) {
  if (loan.status === 'ACTIVE' && loan.overdue) return <StatusBadge status="OVERDUE" />;
  if (loan.status === 'ACTIVE' && loan.dueSoon) return <StatusBadge status="DUE_SOON" />;
  return <StatusBadge status={loan.status} />;
}
