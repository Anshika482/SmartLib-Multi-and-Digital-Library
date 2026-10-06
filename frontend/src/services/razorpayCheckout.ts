import type { PaymentOrder } from '@/types/api';
import type { PaymentReferences } from './paymentService';

/**
 * The provider's own checkout, opened over the page.
 *
 * <p><b>Card details never touch this application.</b> They are typed into the
 * provider's frame, which this code cannot read, and what comes back is three
 * opaque references. There is no field here for a number, an expiry or a CVV,
 * and nothing in this module is stored anywhere.</p>
 *
 * <p>The script is fetched from the provider on first use rather than bundled,
 * because it must be the current one at the moment of payment - a checkout
 * pinned into a build months ago is a checkout with months-old handling of the
 * bank's own rules. Loaded once and reused after that.</p>
 *
 * <p>Kept apart from the screen that calls it so a test can replace the whole
 * module: none of the tests need a key, an account or a network.</p>
 */

/** Where the provider publishes its checkout. */
const CHECKOUT_SCRIPT = 'https://checkout.razorpay.com/v1/checkout.js';

/** How long to wait for that script before giving up. */
const SCRIPT_TIMEOUT_MS = 15000;

/** Why a checkout did not produce a payment. Each needs different words on screen. */
export type CheckoutFailure =
  /** The person closed the window. Nothing was charged. */
  | 'dismissed'
  /** The provider's script would not load - offline, blocked, or down. */
  | 'unavailable'
  /** The provider reported the payment itself as failed. */
  | 'failed';

export class CheckoutError extends Error {
  readonly reason: CheckoutFailure;

  constructor(reason: CheckoutFailure, message: string) {
    super(message);
    this.name = 'CheckoutError';
    this.reason = reason;
  }
}

/** The shape of the provider's global, as much of it as is used here. */
interface RazorpayHandlerResponse {
  razorpay_order_id: string;
  razorpay_payment_id: string;
  razorpay_signature: string;
}

interface RazorpayInstance {
  open: () => void;
  on?: (event: string, handler: (event: unknown) => void) => void;
}

type RazorpayConstructor = new (options: Record<string, unknown>) => RazorpayInstance;

declare global {
  interface Window {
    Razorpay?: RazorpayConstructor;
  }
}

let loading: Promise<RazorpayConstructor> | null = null;

/**
 * The provider's constructor, fetching its script the first time.
 *
 * <p>The promise is cached rather than the result, so two buttons pressed
 * together share one load instead of racing to add two script tags. A failed
 * load is not cached - the next attempt tries again, which is what somebody who
 * has just reconnected expects.</p>
 */
export function loadCheckout(): Promise<RazorpayConstructor> {
  if (window.Razorpay !== undefined) {
    return Promise.resolve(window.Razorpay);
  }

  if (loading !== null) {
    return loading;
  }

  loading = new Promise<RazorpayConstructor>((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${CHECKOUT_SCRIPT}"]`);
    const script = existing ?? document.createElement('script');

    const fail = () =>
      reject(
        new CheckoutError(
          'unavailable',
          'The payment window could not be opened. Check your connection and try again.',
        ),
      );

    const timer = setTimeout(fail, SCRIPT_TIMEOUT_MS);

    script.addEventListener('load', () => {
      clearTimeout(timer);
      if (window.Razorpay === undefined) {
        fail();
        return;
      }
      resolve(window.Razorpay);
    });

    script.addEventListener('error', () => {
      clearTimeout(timer);
      fail();
    });

    if (existing === null) {
      script.src = CHECKOUT_SCRIPT;
      script.async = true;
      document.head.appendChild(script);
    }
  });

  // A load that failed must not be remembered as the answer.
  loading.catch(() => {
    loading = null;
  });

  return loading;
}

/**
 * Opens the checkout for an order and resolves with what the provider says.
 *
 * <p>Everything the provider is told comes from {@code order}, which the server
 * built: the amount, the currency and the order reference. <b>The amount is
 * passed through, never computed here</b> - it is in the smallest currency unit
 * because that is what the provider expects, and the server has already decided
 * what it is.</p>
 *
 * <p>Resolves only when the provider reports a payment. Closing the window
 * rejects with {@code dismissed}, which is not an error worth alarming anybody
 * about: nothing was charged.</p>
 *
 * @param order      the order the server opened
 * @param memberName shown in the window so the payer knows whose fine it is
 */
export async function openCheckout(
  order: PaymentOrder,
  memberName: string,
): Promise<PaymentReferences> {
  const Checkout = await loadCheckout();

  return new Promise<PaymentReferences>((resolve, reject) => {
    let settled = false;

    const finish = (run: () => void) => {
      if (settled) {
        return;
      }
      settled = true;
      run();
    };

    const checkout = new Checkout({
      // The publishable key, straight from the order. Never a literal in this
      // repository, and never the secret - which the browser never receives.
      key: order.keyId,

      // Paise, as the provider requires. The rupee amount is the server's.
      amount: Math.round(order.amount * 100),
      currency: order.currency,
      order_id: order.providerOrderId,

      name: 'SmartLib',
      description: `Library fine for loan #${order.loanId}`,
      prefill: { name: memberName },

      handler: (response: RazorpayHandlerResponse) =>
        finish(() =>
          resolve({
            providerOrderId: response.razorpay_order_id,
            providerPaymentId: response.razorpay_payment_id,
            signature: response.razorpay_signature,
          }),
        ),

      modal: {
        ondismiss: () =>
          finish(() =>
            reject(new CheckoutError('dismissed', 'Payment cancelled. Nothing has been charged.')),
          ),
      },
    });

    // Some versions report a failed payment through an event rather than by
    // closing. Optional, because not every build exposes it.
    checkout.on?.('payment.failed', () =>
      finish(() =>
        reject(
          new CheckoutError('failed', 'The payment did not go through. Your fine has not changed.'),
        ),
      ),
    );

    checkout.open();
  });
}

/** For tests, which need the cached loader forgotten between cases. */
export function resetCheckoutLoaderForTests(): void {
  loading = null;
}
