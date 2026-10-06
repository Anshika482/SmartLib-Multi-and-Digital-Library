import { useState } from 'react';
import { Button } from '@/components/ui/Button';
import { ApiError } from '@/services/apiClient';
import { paymentService } from '@/services/paymentService';
import { CheckoutError, openCheckout } from '@/services/razorpayCheckout';
import type { Transaction } from '@/types/api';

/**
 * Paying one fine, through the provider's own checkout.
 *
 * <p><b>This button cannot settle anything.</b> It opens an order, hands the
 * provider's answer back to the server, and shows whatever the server then
 * says. The fine becomes paid because a signature verified on the server, never
 * because this code decided it had - so a tampered response, a replayed one, or
 * a browser told to skip ahead all end with the fine exactly as it was.</p>
 *
 * <p>Card details are typed into the provider's frame and are never seen by
 * this application. What comes back is three opaque references.</p>
 *
 * <p>Every way this can end has words of its own: paid, cancelled, refused,
 * already settled, no gateway configured, or the connection gone. A single
 * "something went wrong" would leave a person unable to tell whether they had
 * just been charged.</p>
 */
export function PayFineButton({
  loan,
  payerName,
  onPaid,
}: {
  loan: Transaction;
  payerName: string;
  onPaid: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ tone: 'ok' | 'bad' | 'plain'; text: string } | null>(null);

  async function pay() {
    // One attempt at a time. The server tolerates a repeat - it reuses the open
    // order and settles nothing twice - but a second modal over the first is
    // only confusing.
    if (busy) {
      return;
    }

    setBusy(true);
    setMessage(null);

    try {
      // 1. The server opens the order, and decides the amount from the loan.
      const order = await paymentService.createOrder(loan.id);

      // 2. The provider takes the money. Nothing here sees a card.
      const references = await openCheckout(order, payerName);

      // 3. The server checks the signature and, only then, settles the fine.
      const result = await paymentService.verify(loan.id, references);

      if (result.finePaymentStatus === 'PAID') {
        setMessage({ tone: 'ok', text: 'Paid. Thank you.' });
        // Re-read from the server rather than editing the row here: what the
        // fine is now is the server's to say.
        onPaid();
      } else {
        setMessage({
          tone: 'bad',
          text: 'The payment was taken but the fine is not settled. Please speak to library staff.',
        });
      }
    } catch (error) {
      setMessage(explain(error));

      // An already-settled fine means this screen is stale, whoever settled it.
      if (error instanceof ApiError && error.status === 409) {
        onPaid();
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Button busy={busy} disabled={busy} onClick={() => void pay()}>
        {busy ? 'Paying' : 'Pay fine'}
      </Button>

      {message !== null && (
        <span
          className={`sl-pay__message sl-pay__message--${message.tone}`}
          role={message.tone === 'bad' ? 'alert' : 'status'}
        >
          {message.text}
        </span>
      )}
    </>
  );
}

/**
 * What to say about a failure.
 *
 * <p>The API's own message is preferred wherever it has one, because it knows
 * things this screen cannot - that the fine was settled at the desk a minute
 * ago, for instance. The statuses are mapped rather than passed through raw
 * where the difference changes what a person should do next.</p>
 */
function explain(error: unknown): { tone: 'ok' | 'bad' | 'plain'; text: string } {
  if (error instanceof CheckoutError) {
    // Cancelling is not a failure, and must not be dressed as one: nothing was
    // charged and nothing is wrong.
    return { tone: error.reason === 'dismissed' ? 'plain' : 'bad', text: error.message };
  }

  if (error instanceof ApiError) {
    switch (error.status) {
      case 503:
        return {
          tone: 'bad',
          text: 'Online payment is not available here yet. Please pay at the desk.',
        };
      case 409:
        // Already paid, or the fine changed under us. The API says which.
        return { tone: 'plain', text: error.message };
      case 400:
        return {
          tone: 'bad',
          text: 'That payment could not be verified, so your fine has not changed. Nothing was settled.',
        };
      case 403:
      case 404:
        return { tone: 'bad', text: 'This fine cannot be paid from this account.' };
      default:
        return { tone: 'bad', text: error.message };
    }
  }

  // A dropped connection, most likely. Deliberately does not claim the payment
  // failed: it may have succeeded and the answer been lost, and trying again is
  // safe because the server settles nothing twice.
  return {
    tone: 'bad',
    text: 'We could not reach the library. If you were charged, check your fines again before retrying.',
  };
}
