import { useEffect, useState } from 'react';
import { Building2, CreditCard, FlaskConical, Lock, Smartphone } from 'lucide-react';
import { meApi } from '@/api/endpoints';
import { isApiError } from '@/api/http';
import type { CheckoutOrder } from '@/api/types';
import { Field, Input, Select } from '@/components/form';
import { Modal } from '@/components/modal';
import { Alert, Badge, Button, Segmented, Spinner } from '@/components/ui';
import { money } from '@/lib/format';

export interface CheckoutSuccess { razorpay_order_id: string; razorpay_payment_id: string; razorpay_signature: string }
type Method = 'UPI' | 'CARD' | 'NETBANKING';

const banks = ['State Bank of India', 'HDFC Bank', 'ICICI Bank', 'Axis Bank', 'Kotak Mahindra Bank', 'Punjab National Bank'];

/**
 * Checkout window for the built-in demo gateway (used when no Razorpay keys are configured). It looks and behaves like a
 * real checkout, but no money moves and the sample details are never sent anywhere: the server only learns the chosen
 * method, then issues a signed result that SmartLib verifies before marking the fine paid.
 */
export function DemoCheckout({ order, onSuccess, onFailure, onCancel }:
  { order: CheckoutOrder | null; onSuccess: (r: CheckoutSuccess) => void; onFailure: (message: string) => void; onCancel: () => void }) {
  const [method, setMethod] = useState<Method>('UPI');
  const [upi, setUpi] = useState('');
  const [card, setCard] = useState({ number: '4111 1111 1111 1111', expiry: '12/30', cvv: '123', name: '' });
  const [bank, setBank] = useState(banks[0]);
  const [processing, setProcessing] = useState<null | 'pay' | 'fail'>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!order) return;
    setMethod('UPI');
    setUpi(`${order.prefill.name.split(' ')[0].toLowerCase().replace(/[^a-z0-9]/g, '') || 'reader'}@okdemo`);
    setCard((c) => ({ ...c, name: order.prefill.name }));
    setProcessing(null);
    setError(null);
  }, [order]);

  const invalid = method === 'UPI' ? !/^[\w.-]{2,}@[a-z]{2,}$/i.test(upi.trim())
    : method === 'CARD' ? card.number.replace(/\s/g, '').length !== 16 || !/^\d{2}\/\d{2}$/.test(card.expiry) || !/^\d{3}$/.test(card.cvv)
      : !bank;

  const submit = async (succeed: boolean) => {
    if (!order) return;
    setError(null);
    setProcessing(succeed ? 'pay' : 'fail');
    try {
      await new Promise((r) => setTimeout(r, 900)); // feels like a real bank round-trip
      const r = await meApi.demoCheckout({ orderId: order.orderId, method, succeed });
      if (r.status === 'AUTHORIZED' && r.paymentId && r.signature) {
        onSuccess({ razorpay_order_id: r.orderId, razorpay_payment_id: r.paymentId, razorpay_signature: r.signature });
      } else {
        onFailure(r.message ?? 'The payment was declined. You have not been charged.');
      }
    } catch (e) {
      setError(isApiError(e) ? e.message : 'Something went wrong. Please try again.');
      setProcessing(null);
    }
  };

  return (
    <Modal open={!!order} onOpenChange={(o) => !o && !processing && onCancel()} size="md"
      title={<span className="flex items-center gap-2"><Lock className="size-4 text-accent-2" aria-hidden />SmartLib Pay
        <Badge tone="amber" icon={<FlaskConical className="size-3" />}>Demo mode</Badge></span>}
      description="No real money moves. Use the sample details below.">
      {order && (
        <div className="space-y-5 px-5 py-5">
          <div className="flex items-end justify-between gap-4 rounded-xl border border-line bg-ink/50 p-4">
            <div className="min-w-0">
              <p className="text-xs uppercase tracking-wider text-faint">Paying SmartLib</p>
              <p className="mt-1 text-sm text-muted">{order.description}</p>
            </div>
            <p className="shrink-0 text-2xl font-bold text-fg">{money(order.amountPaise)}</p>
          </div>

          <Segmented label="Payment method" value={method} onChange={setMethod} options={[
            { value: 'UPI', label: 'UPI' }, { value: 'CARD', label: 'Card' }, { value: 'NETBANKING', label: 'Net banking' }]} />

          {method === 'UPI' && (
            <Field label="UPI ID" htmlFor="demo-upi" hint="Any UPI-style ID works in demo mode">
              <Input id="demo-upi" leading={<Smartphone className="size-4" />} value={upi} onChange={(e) => setUpi(e.target.value)} autoComplete="off" />
            </Field>
          )}
          {method === 'CARD' && (
            <div className="space-y-3">
              <Field label="Card number" htmlFor="demo-card" hint="Sample card: never enter a real card here">
                <Input id="demo-card" leading={<CreditCard className="size-4" />} inputMode="numeric" autoComplete="off" value={card.number}
                  onChange={(e) => setCard({ ...card, number: e.target.value.replace(/[^\d ]/g, '').slice(0, 19) })} />
              </Field>
              <div className="grid grid-cols-3 gap-3">
                <Field label="Expiry" htmlFor="demo-exp"><Input id="demo-exp" autoComplete="off" value={card.expiry} onChange={(e) => setCard({ ...card, expiry: e.target.value.slice(0, 5) })} /></Field>
                <Field label="CVV" htmlFor="demo-cvv"><Input id="demo-cvv" inputMode="numeric" autoComplete="off" value={card.cvv} onChange={(e) => setCard({ ...card, cvv: e.target.value.replace(/\D/g, '').slice(0, 3) })} /></Field>
                <Field label="Name" htmlFor="demo-name"><Input id="demo-name" autoComplete="off" value={card.name} onChange={(e) => setCard({ ...card, name: e.target.value })} /></Field>
              </div>
            </div>
          )}
          {method === 'NETBANKING' && (
            <Field label="Bank" htmlFor="demo-bank">
              <Select id="demo-bank" value={bank} onChange={(e) => setBank(e.target.value)}>
                {banks.map((b) => <option key={b}>{b}</option>)}
              </Select>
            </Field>
          )}

          {error && <Alert tone="error">{error}</Alert>}
          {processing && (
            <p className="flex items-center gap-2 text-sm text-muted" role="status">
              <Spinner className="size-4" />{processing === 'pay' ? 'Processing payment with your bank…' : 'Contacting your bank…'}
            </p>
          )}

          <div className="flex flex-col-reverse gap-2 sm:flex-row sm:items-center sm:justify-between">
            <button type="button" className="text-sm text-muted underline-offset-4 hover:text-danger hover:underline disabled:opacity-50"
              disabled={!!processing} onClick={() => submit(false)}>Simulate a failed payment</button>
            <Button size="lg" disabled={invalid || !!processing} loading={processing === 'pay'} onClick={() => submit(true)}
              icon={method === 'NETBANKING' ? <Building2 className="size-4" /> : <Lock className="size-4" />}>
              Pay {money(order.amountPaise)}
            </Button>
          </div>
          <p className="text-[11px] leading-snug text-faint">
            Demo gateway: SmartLib's server issues a signed result and verifies it before the fine is marked paid, the same check it does for Razorpay.
            Card and UPI details stay in this window and are never sent anywhere.
          </p>
        </div>
      )}
    </Modal>
  );
}
