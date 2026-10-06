/** @vitest-environment jsdom */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PayFineButton } from './PayFineButton';
import { paymentService } from '@/services/paymentService';
import * as checkout from '@/services/razorpayCheckout';
import { CheckoutError } from '@/services/razorpayCheckout';
import { ApiError } from '@/services/apiClient';
import type { PaymentOrder, PaymentResult, Transaction } from '@/types/api';

/**
 * Paying a fine, every way it can end.
 *
 * <p><b>No credentials, no network, no provider.</b> The checkout module is
 * replaced wholesale, so these tests need no Razorpay account and reach
 * nothing: what is under test is the sequence this screen performs and what it
 * says afterwards, which is all that belongs in the browser anyway.</p>
 *
 * <p>The property that matters most: <b>this button cannot settle a fine</b>.
 * Several tests below have the provider report a perfectly good payment and the
 * server refuse it, and require the screen to say so rather than to show
 * success. The fine is paid when the server says it is paid and at no other
 * moment.</p>
 */

const createOrder = vi.spyOn(paymentService, 'createOrder');
const verify = vi.spyOn(paymentService, 'verify');
const openCheckout = vi.spyOn(checkout, 'openCheckout');

const REFERENCES = {
  providerOrderId: 'order_test_1',
  providerPaymentId: 'pay_test_1',
  signature: 'signature_from_the_provider',
};

function order(overrides: Partial<PaymentOrder> = {}): PaymentOrder {
  return {
    paymentId: 5,
    provider: 'sandbox',
    providerOrderId: 'order_test_1',
    // A publishable key only, and a fake one: nothing here is a secret.
    keyId: 'test_key_id_not_a_secret',
    amount: 1.05,
    currency: 'INR',
    loanId: 7,
    ...overrides,
  };
}

function result(overrides: Partial<PaymentResult> = {}): PaymentResult {
  return {
    paymentId: 5,
    status: 'SUCCEEDED',
    providerPaymentId: 'pay_test_1',
    loanId: 7,
    finePaymentStatus: 'PAID',
    paidAt: '2026-09-24T10:00:00',
    ...overrides,
  };
}

const loan: Transaction = {
  id: 7,
  bookId: 101,
  bookTitle: 'A Book',
  bookAuthor: 'An Author',
  userId: 1,
  issueDate: '2026-09-01',
  dueDate: '2026-09-15',
  returnDate: '2026-09-18',
  fineAmount: 1.05,
  daysOverdue: 3,
  status: 'RETURNED',
  finePaymentStatus: 'UNPAID',
  finePaidAt: null,
};

let onPaid: ReturnType<typeof vi.fn>;

function renderButton() {
  onPaid = vi.fn();
  return render(<PayFineButton loan={loan} payerName="asha" onPaid={onPaid} />);
}

async function clickPay() {
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Pay fine' }));
}

beforeEach(() => {
  createOrder.mockReset();
  verify.mockReset();
  openCheckout.mockReset();

  createOrder.mockResolvedValue(order());
  openCheckout.mockResolvedValue(REFERENCES);
  verify.mockResolvedValue(result());
});

// ---------- the happy path ----------

describe('a payment that works', () => {
  it('opens an order, then the checkout, then verifies - in that order', async () => {
    renderButton();
    await clickPay();

    await waitFor(() => expect(verify).toHaveBeenCalled());

    expect(createOrder).toHaveBeenCalledWith(7);
    expect(openCheckout).toHaveBeenCalled();
    expect(verify).toHaveBeenCalledWith(7, REFERENCES);

    // The order was opened before the window, and the window before the check.
    expect(createOrder.mock.invocationCallOrder[0]).toBeLessThan(openCheckout.mock.invocationCallOrder[0]);
    expect(openCheckout.mock.invocationCallOrder[0]).toBeLessThan(verify.mock.invocationCallOrder[0]);
  });

  it('says it is paid and asks the screen to re-read from the server', async () => {
    renderButton();
    await clickPay();

    expect(await screen.findByText('Paid. Thank you.')).toBeInTheDocument();
    expect(onPaid).toHaveBeenCalledTimes(1);
  });

  it('sends the provider the order the server opened, and never an amount of its own', async () => {
    renderButton();
    await clickPay();

    await waitFor(() => expect(openCheckout).toHaveBeenCalled());

    // Whatever is shown, the figure came from the server's order object.
    const passed = openCheckout.mock.calls[0][0];
    expect(passed.amount).toBe(1.05);
    expect(passed.providerOrderId).toBe('order_test_1');
  });

  it('never sends a card detail anywhere: it has none to send', async () => {
    renderButton();
    await clickPay();

    await waitFor(() => expect(verify).toHaveBeenCalled());

    // The whole payload, checked. Three opaque references and nothing else.
    const sent = JSON.stringify(verify.mock.calls[0][1]);
    expect(Object.keys(verify.mock.calls[0][1])).toEqual([
      'providerOrderId',
      'providerPaymentId',
      'signature',
    ]);
    for (const forbidden of ['card', 'cvv', 'expiry', 'pan', 'number', 'account']) {
      expect(sent.toLowerCase()).not.toContain(forbidden);
    }
  });
});

// ---------- the ways it can end badly ----------

describe('when the payment does not complete', () => {
  it('treats closing the window as a cancellation, not a failure', async () => {
    openCheckout.mockRejectedValue(
      new CheckoutError('dismissed', 'Payment cancelled. Nothing has been charged.'),
    );
    renderButton();
    await clickPay();

    expect(await screen.findByText('Payment cancelled. Nothing has been charged.')).toBeInTheDocument();

    // Not an alert: nothing went wrong and nothing was charged.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(verify).not.toHaveBeenCalled();
    expect(onPaid).not.toHaveBeenCalled();
  });

  it('says so when the provider window cannot be opened at all', async () => {
    openCheckout.mockRejectedValue(
      new CheckoutError('unavailable', 'The payment window could not be opened. Check your connection and try again.'),
    );
    renderButton();
    await clickPay();

    expect(await screen.findByRole('alert')).toHaveTextContent('could not be opened');
    expect(verify).not.toHaveBeenCalled();
  });

  it('says the fine is unchanged when the provider reports a failed payment', async () => {
    openCheckout.mockRejectedValue(
      new CheckoutError('failed', 'The payment did not go through. Your fine has not changed.'),
    );
    renderButton();
    await clickPay();

    expect(await screen.findByRole('alert')).toHaveTextContent('has not changed');
    expect(onPaid).not.toHaveBeenCalled();
  });
});

// ---------- the server is the authority ----------

describe('the server decides, not this screen', () => {
  it('shows no success when verification is refused, even though the provider said yes', async () => {
    verify.mockRejectedValue(new ApiError(400, 'Payment verification failed'));
    renderButton();
    await clickPay();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('could not be verified');
    expect(alert).toHaveTextContent('Nothing was settled');

    // The provider reported a good payment and the screen still does not claim
    // the fine is paid: only a verified signature does that.
    expect(screen.queryByText('Paid. Thank you.')).not.toBeInTheDocument();
    expect(onPaid).not.toHaveBeenCalled();
  });

  it('does not claim success when the server settles nothing despite verifying', async () => {
    verify.mockResolvedValue(result({ status: 'SUCCEEDED', finePaymentStatus: 'UNPAID' }));
    renderButton();
    await clickPay();

    expect(await screen.findByRole('alert')).toHaveTextContent('speak to library staff');
    expect(screen.queryByText('Paid. Thank you.')).not.toBeInTheDocument();
  });

  it("passes on the server's own words when the fine was already settled", async () => {
    verify.mockRejectedValue(new ApiError(409, 'This fine has already been paid.'));
    renderButton();
    await clickPay();

    expect(await screen.findByText('This fine has already been paid.')).toBeInTheDocument();

    // The screen is stale, whoever settled it - so re-read.
    expect(onPaid).toHaveBeenCalledTimes(1);
  });

  it('sends people to the desk when this deployment has no gateway', async () => {
    createOrder.mockRejectedValue(new ApiError(503, 'Payment gateway is not configured'));
    renderButton();
    await clickPay();

    expect(await screen.findByRole('alert')).toHaveTextContent('pay at the desk');
    expect(openCheckout).not.toHaveBeenCalled();
  });

  it('refuses politely when the fine is not this account’s to pay', async () => {
    createOrder.mockRejectedValue(new ApiError(403, 'Access denied'));
    renderButton();
    await clickPay();

    expect(await screen.findByRole('alert')).toHaveTextContent('cannot be paid from this account');
  });

  it('does not claim a payment failed when the connection dropped', async () => {
    // It may have succeeded and the answer been lost. Saying "payment failed"
    // would be a guess, and the wrong one half the time.
    verify.mockRejectedValue(new TypeError('Failed to fetch'));
    renderButton();
    await clickPay();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('If you were charged, check your fines again');
    expect(alert).not.toHaveTextContent('failed');
  });
});

// ---------- pressing it twice ----------

describe('repeated submission', () => {
  it('disables itself while a payment is in flight', async () => {
    createOrder.mockReturnValue(new Promise(() => {}));
    renderButton();
    await clickPay();

    await waitFor(() => expect(screen.getByRole('button', { name: 'Paying' })).toBeDisabled());
  });

  it('opens only one order however many times it is pressed', async () => {
    const user = userEvent.setup();
    createOrder.mockReturnValue(new Promise(() => {}));
    renderButton();

    const button = screen.getByRole('button', { name: 'Pay fine' });
    await user.click(button);
    await user.click(button);
    await user.click(button);

    expect(createOrder).toHaveBeenCalledTimes(1);
  });

  it('can be used again after a cancellation', async () => {
    openCheckout.mockRejectedValueOnce(
      new CheckoutError('dismissed', 'Payment cancelled. Nothing has been charged.'),
    );
    renderButton();
    await clickPay();
    await screen.findByText('Payment cancelled. Nothing has been charged.');

    // Cancelling leaves the order open on the server, so trying again resumes
    // rather than duplicating - and the button has to be usable for that.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Pay fine' })).toBeEnabled());

    await clickPay();
    await waitFor(() => expect(createOrder).toHaveBeenCalledTimes(2));
  });

  it('clears the previous message when tried again', async () => {
    openCheckout.mockRejectedValueOnce(
      new CheckoutError('dismissed', 'Payment cancelled. Nothing has been charged.'),
    );
    renderButton();
    await clickPay();
    await screen.findByText('Payment cancelled. Nothing has been charged.');

    await clickPay();

    await waitFor(() =>
      expect(screen.queryByText('Payment cancelled. Nothing has been charged.')).not.toBeInTheDocument(),
    );
  });
});
