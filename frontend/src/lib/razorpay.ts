import type { CheckoutOrder } from '@/api/types';

interface RazorpaySuccess {
  razorpay_payment_id: string;
  razorpay_order_id: string;
  razorpay_signature: string;
}

declare global {
  interface Window {
    Razorpay?: new (options: Record<string, unknown>) => { open: () => void; on: (event: string, cb: (r: unknown) => void) => void };
  }
}

let loader: Promise<void> | null = null;

function loadCheckout(): Promise<void> {
  if (window.Razorpay) return Promise.resolve();
  if (!loader) {
    loader = new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = 'https://checkout.razorpay.com/v1/checkout.js';
      s.async = true;
      s.onload = () => resolve();
      s.onerror = () => {
        loader = null;
        reject(new Error('Could not load the Razorpay checkout. Check your connection and try again.'));
      };
      document.body.appendChild(s);
    });
  }
  return loader;
}

export class CheckoutCancelled extends Error {}
export class CheckoutFailed extends Error {}

/** Opens Razorpay Checkout. Resolves with the signed result; the SERVER verifies it before anything is marked paid. */
export async function openCheckout(order: CheckoutOrder): Promise<RazorpaySuccess> {
  await loadCheckout();
  return new Promise((resolve, reject) => {
    const rzp = new window.Razorpay!({
      key: order.keyId,
      order_id: order.orderId,
      amount: order.amountPaise,
      currency: order.currency,
      name: 'SmartLib',
      description: order.description,
      image: `${window.location.origin}/brand/smartlib-mark-192.png`,
      prefill: { name: order.prefill.name, email: order.prefill.email, contact: order.prefill.contact ?? '' },
      theme: { color: '#1688FF' },
      handler: (r: RazorpaySuccess) => resolve(r),
      modal: { ondismiss: () => reject(new CheckoutCancelled('Payment was cancelled.')) },
    });
    rzp.on('payment.failed', (r: unknown) => {
      const desc = (r as { error?: { description?: string } })?.error?.description;
      reject(new CheckoutFailed(desc ?? 'The payment failed. You have not been charged.'));
    });
    rzp.open();
  });
}
