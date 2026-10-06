import { api } from './apiClient';
import type { PaymentOrder, PaymentResult } from '@/types/api';

/**
 * Paying a fine.
 *
 * <p>Two calls, and neither carries money. The server decides the amount from
 * the loan when it opens the order, and decides whether a payment is genuine by
 * checking the provider's signature with a secret this code has never seen.
 * <b>Nothing here can mark a fine paid</b> - the only thing that does is a
 * signature the server verified.</p>
 *
 * <p>No card number, expiry or CVV passes through this module or any other in
 * this application. Those are typed into the provider's own checkout, which
 * runs in its own frame; what comes back is three opaque references.</p>
 */

export interface PaymentReferences {
  providerOrderId: string;
  providerPaymentId: string;
  signature: string;
}

export const paymentService = {
  /**
   * Opens an order for a loan's outstanding fine, or returns the one already
   * open.
   *
   * <p>Asking twice does not open two orders - the server reuses the one that
   * is still outstanding - so a double click costs nothing and a retry after a
   * dropped connection resumes rather than duplicates.</p>
   */
  createOrder(loanId: number, signal?: AbortSignal): Promise<PaymentOrder> {
    return api.post<PaymentOrder>(`/api/transactions/${loanId}/payment-order`, {}, { signal });
  },

  /**
   * Hands the provider's answer to the server to be checked.
   *
   * <p>The fine is settled by the server if, and only if, the signature is the
   * provider's over those references. Sending the same verified references
   * again is safe: the server answers the same thing and settles nothing
   * twice.</p>
   */
  verify(loanId: number, references: PaymentReferences, signal?: AbortSignal): Promise<PaymentResult> {
    return api.post<PaymentResult>(`/api/transactions/${loanId}/payment-verification`, references, { signal });
  },
};
