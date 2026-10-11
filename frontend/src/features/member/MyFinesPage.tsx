import { useRef, useState } from 'react';
import { Link } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { CheckCircle2, CreditCard, HandCoins, Loader2, ShieldCheck } from 'lucide-react';
import { meApi } from '@/api/endpoints';
import { isApiError } from '@/api/http';
import type { CheckoutOrder, FineView } from '@/api/types';
import { openCheckout, CheckoutCancelled, CheckoutFailed } from '@/lib/razorpay';
import { EmptyState, QueryState } from '@/components/states';
import { Alert, Button, PageHeader, Segmented, StatCard } from '@/components/ui';
import { StatusBadge } from '@/components/status';
import { DataTable } from '@/components/table';
import { date, money } from '@/lib/format';
import { DemoCheckout, type CheckoutSuccess } from './DemoCheckout';

type PayState = { fineId: number; stage: 'creating' | 'checkout' | 'verifying' | 'done' | 'failed' | 'cancelled'; message?: string };

export default function MyFinesPage() {
  const qc = useQueryClient();
  const [status, setStatus] = useState<'UNPAID' | 'ALL'>('UNPAID');
  const fines = useQuery({ queryKey: ['me', 'fines', status], queryFn: () => meApi.fines(status) });
  const summary = useQuery({ queryKey: ['me', 'fines', 'summary'], queryFn: meApi.fineSummary });
  const [pay, setPay] = useState<PayState | null>(null);
  // Demo gateway window (used when the server has no Razorpay keys): resolves like Razorpay's checkout handler.
  const [demoOrder, setDemoOrder] = useState<CheckoutOrder | null>(null);
  const demoWait = useRef<{ resolve: (r: CheckoutSuccess) => void; reject: (e: Error) => void } | null>(null);
  const openDemo = (order: CheckoutOrder) => new Promise<CheckoutSuccess>((resolve, reject) => {
    demoWait.current = { resolve, reject };
    setDemoOrder(order);
  });
  const closeDemo = () => {
    demoWait.current = null;
    setDemoOrder(null);
  };

  const startPayment = async (fine: FineView) => {
    setPay({ fineId: fine.id, stage: 'creating' });
    try {
      const order = await meApi.createOrder(fine.id);
      setPay({ fineId: fine.id, stage: 'checkout' });
      const result = order.provider === 'DEMO' ? await openDemo(order) : await openCheckout(order);
      setPay({ fineId: fine.id, stage: 'verifying' });
      const verified = await meApi.verifyPayment({ razorpayOrderId: result.razorpay_order_id, razorpayPaymentId: result.razorpay_payment_id, razorpaySignature: result.razorpay_signature });
      if (verified.status !== 'PAID') throw new Error('Payment could not be confirmed.');
      setPay({ fineId: fine.id, stage: 'done', message: `Payment of ${money(verified.amountPaise)} verified.` });
      toast.success('Payment verified — fine marked as paid.');
    } catch (e) {
      if (e instanceof CheckoutCancelled) setPay({ fineId: fine.id, stage: 'cancelled', message: 'Payment cancelled. You have not been charged.' });
      else if (e instanceof CheckoutFailed) setPay({ fineId: fine.id, stage: 'failed', message: e.message });
      else setPay({ fineId: fine.id, stage: 'failed', message: isApiError(e) ? e.message : (e as Error).message });
    } finally {
      qc.invalidateQueries({ queryKey: ['me'] });
    }
  };

  const busy = pay && ['creating', 'checkout', 'verifying'].includes(pay.stage);
  return (
    <>
      <PageHeader title="Fines" subtitle="Pay online with UPI, card or net banking. A fine shows as paid only after SmartLib's server verifies the payment." icon={<HandCoins className="size-5" />}
        actions={<Link to="/app/member/payments" className="btn btn-outline"><CreditCard className="size-4" />Payment history</Link>} />
      {summary.data && (
        <div className="mb-6 grid gap-4 sm:grid-cols-3">
          <StatCard label="Outstanding" value={money(summary.data.unpaidPaise)} hint={`${summary.data.unpaidCount} unpaid`} icon={<HandCoins className="size-5" />} tone={summary.data.unpaidPaise ? 'red' : 'green'} />
          <StatCard label="Paid" value={money(summary.data.paidPaise)} icon={<CheckCircle2 className="size-5" />} tone="green" />
          <StatCard label="Waived" value={money(summary.data.waivedPaise)} icon={<ShieldCheck className="size-5" />} tone="violet" />
        </div>
      )}
      {pay && pay.stage !== 'creating' && pay.stage !== 'checkout' && (
        <Alert className="mb-5" tone={pay.stage === 'done' ? 'success' : pay.stage === 'verifying' ? 'info' : pay.stage === 'cancelled' ? 'warning' : 'error'}
          title={pay.stage === 'verifying' ? 'Verifying your payment with the server…' : pay.stage === 'done' ? 'Payment successful' : pay.stage === 'cancelled' ? 'Payment cancelled' : 'Payment not completed'}>
          {pay.message}
        </Alert>
      )}
      <Segmented label="Filter fines" value={status} onChange={setStatus} className="mb-5" options={[{ value: 'UNPAID', label: 'Unpaid' }, { value: 'ALL', label: 'All fines' }]} />
      <div className="glass p-4">
        <QueryState query={fines} isEmpty={(d) => d.length === 0}
          empty={<EmptyState icon={<CheckCircle2 className="size-6" />} title={status === 'UNPAID' ? 'No outstanding fines' : 'No fines yet'} description="Return books on time to keep it that way." />}>
          {(rows) => (
            <DataTable caption="Your fines" rows={rows} rowKey={(f) => f.id} columns={[
              { key: 'what', header: 'Fine', primary: true, cell: (f) => (
                <div><p className="font-semibold text-fg">{f.type === 'OVERDUE' ? 'Overdue return' : f.type === 'LOST' ? 'Lost book' : f.type === 'DAMAGED' ? 'Damaged book' : 'Library fine'}</p>
                  <p className="text-xs text-faint">{f.book?.title ?? f.reason}</p></div>) },
              { key: 'library', header: 'Library', cell: (f) => f.library.name },
              { key: 'reason', header: 'Details', cell: (f) => <span className="text-xs">{f.reason}{f.overdueDays ? ` (${f.overdueDays} days)` : ''}</span> },
              { key: 'date', header: 'Date', sortValue: (f) => f.createdAt, cell: (f) => date(f.createdAt) },
              { key: 'amount', header: 'Amount', align: 'right', sortValue: (f) => f.amountPaise, cell: (f) => <span className="font-semibold text-fg">{money(f.amountPaise)}</span> },
              { key: 'status', header: 'Status', cell: (f) => <StatusBadge status={f.status} /> },
              { key: 'actions', header: 'Action', align: 'right', cell: (f) => f.status === 'UNPAID' ? (
                <Button size="sm" onClick={() => startPayment(f)} disabled={!!busy} loading={!!busy && pay?.fineId === f.id}
                  icon={pay?.fineId === f.id && pay.stage === 'verifying' ? <Loader2 className="size-4 animate-spin" /> : <CreditCard className="size-4" />}>
                  {pay?.fineId === f.id && (pay.stage === 'failed' || pay.stage === 'cancelled') ? 'Retry payment' : 'Pay now'}
                </Button>
              ) : f.status === 'WAIVED' ? <span className="text-xs text-faint">{f.waivedReason}</span> : <span className="text-xs text-success">Paid {date(f.paidAt)}</span> },
            ]} />
          )}
        </QueryState>
      </div>
      <p className="mt-3 flex items-center gap-1.5 text-xs text-faint"><ShieldCheck className="size-3.5" />Payments are processed by Razorpay, or by the built-in demo gateway when Razorpay isn't set up. SmartLib never sees your card or UPI details.</p>
      <DemoCheckout order={demoOrder}
        onSuccess={(r) => { demoWait.current?.resolve(r); closeDemo(); }}
        onFailure={(m) => { demoWait.current?.reject(new CheckoutFailed(m)); closeDemo(); }}
        onCancel={() => { demoWait.current?.reject(new CheckoutCancelled('Payment was cancelled.')); closeDemo(); }} />
    </>
  );
}
